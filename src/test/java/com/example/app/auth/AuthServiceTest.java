package com.example.app.auth;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.dto.AuthResponse;
import com.example.app.auth.dto.LoginDto;
import com.example.app.auth.dto.RegistrationDto;
import com.example.app.user.User;
import com.example.app.user.UserRepository;
import com.example.app.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Duration TTL = Duration.ofDays(7);

    @Mock UserRepository users;
    @Mock AuthTokenRepository tokens;
    @Mock AuditService audit;

    // A real (fast) BCrypt encoder, spied so calls can be verified.
    PasswordEncoder encoder;
    AuthService service;

    @BeforeEach
    void setUp() {
        encoder = spy(new BCryptPasswordEncoder(4));
        service = new AuthService(users, tokens, encoder, audit, TTL);
    }

    private void saveAssignsId(long id) {
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            setField(u, "id", id);
            return u;
        });
    }

    private static User user(long id, String username, String hash) {
        User u = new User(username, hash);
        setField(u, "id", id);
        return u;
    }

    // ---- register ----

    @Test
    void register_trimsAndLowerCasesUsername() {
        when(users.existsByUsername("alice_1")).thenReturn(false);
        saveAssignsId(42L);

        AuthResponse res = service.register(new RegistrationDto("  Alice_1 ", "password123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice_1");
        assertThat(res.username()).isEqualTo("alice_1");
        assertThat(res.userId()).isEqualTo(42L);
    }

    @Test
    void register_duplicateUsername_is409() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegistrationDto("ALICE", "password123")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Username is already taken");
                });
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(tokens, audit);
    }

    @Test
    void register_uniqueConstraintRace_is409() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_username"));

        assertThatThrownBy(() -> service.register(new RegistrationDto("alice", "password123")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Username is already taken");
                });
        verifyNoInteractions(tokens, audit);
    }

    @Test
    void register_passwordOver72Bytes_is400() {
        String password = "é".repeat(40); // 40 characters, 80 UTF-8 bytes

        assertThatThrownBy(() -> service.register(new RegistrationDto("alice", password)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Password must be at most 72 bytes");
                });
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void register_storesBcryptHashNeverRawPassword() {
        when(users.existsByUsername("alice")).thenReturn(false);
        saveAssignsId(1L);

        service.register(new RegistrationDto("alice", "password123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        String hash = saved.getValue().getPasswordHash();
        assertThat(hash).isNotEqualTo("password123").doesNotContain("password123").startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches("password123", hash)).isTrue();
        verify(encoder).encode("password123");
    }

    @Test
    void register_savesOnlySha256OfRandomToken() {
        when(users.existsByUsername("alice")).thenReturn(false);
        saveAssignsId(1L);

        Instant before = Instant.now();
        AuthResponse res = service.register(new RegistrationDto("alice", "password123"));

        ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokens).save(saved.capture());
        AuthToken token = saved.getValue();
        assertThat(res.token()).isNotBlank();
        assertThat(Base64.getUrlDecoder().decode(res.token())).hasSize(32);
        assertThat(token.getTokenHash())
                .isEqualTo(AuthService.sha256Hex(res.token()))
                .isNotEqualTo(res.token())
                .matches("[0-9a-f]{64}");
        assertThat(token.getUser().getId()).isEqualTo(1L);
        assertThat(token.getExpiresAt()).isCloseTo(before.plus(TTL), within(Duration.ofSeconds(5)));
    }

    @Test
    void register_auditsUserRegistered() {
        when(users.existsByUsername("alice")).thenReturn(false);
        saveAssignsId(42L);

        service.register(new RegistrationDto(" Alice ", "password123"));

        verify(audit).record(42L, AuditAction.USER_REGISTERED, null, "username=alice");
    }

    // ---- login ----

    @Test
    void login_success_normalizesUsernameAndIssuesFreshTokens() {
        User alice = user(7L, "alice", encoder.encode("password123"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));

        AuthResponse first = service.login(new LoginDto("  ALICE ", "password123"));
        AuthResponse second = service.login(new LoginDto("alice", "password123"));

        assertThat(first.userId()).isEqualTo(7L);
        assertThat(first.username()).isEqualTo("alice");
        assertThat(first.token()).isNotEqualTo(second.token());
        ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokens, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AuthToken::getTokenHash)
                .containsExactly(AuthService.sha256Hex(first.token()), AuthService.sha256Hex(second.token()));
        verifyNoInteractions(audit);
    }

    @Test
    void login_wrongPasswordAndUnknownUser_giveSame401() {
        User alice = user(7L, "alice", encoder.encode("password123"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());

        ApiException wrongPassword = catchApi(() -> service.login(new LoginDto("alice", "wrong-password")));
        ApiException unknownUser = catchApi(() -> service.login(new LoginDto("nobody", "password123")));

        assertThat(wrongPassword.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getMessage()).isEqualTo("Invalid username or password");
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(tokens, never()).save(any());
    }

    @Test
    void login_unknownUser_stillChecksAPasswordHash() {
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());

        catchApi(() -> service.login(new LoginDto("nobody", "password123")));

        // Equalises timing with the wrong-password path.
        verify(encoder).matches(eq("password123"), anyString());
    }

    // ---- authenticate ----

    @Test
    void authenticate_validToken_returnsUser() {
        User alice = user(7L, "alice", "hash");
        when(tokens.findByTokenHash(AuthService.sha256Hex("raw-token")))
                .thenReturn(Optional.of(new AuthToken(alice, "ignored", Instant.now().plusSeconds(60))));

        assertThat(service.authenticate("raw-token")).contains(new CurrentUser(7L, "alice"));
    }

    @Test
    void authenticate_expiredToken_isRejected() {
        User alice = user(7L, "alice", "hash");
        when(tokens.findByTokenHash(AuthService.sha256Hex("raw-token")))
                .thenReturn(Optional.of(new AuthToken(alice, "ignored", Instant.now().minusSeconds(1))));

        assertThat(service.authenticate("raw-token")).isEmpty();
    }

    @Test
    void authenticate_unknownToken_isEmpty() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.authenticate("nope")).isEmpty();
    }

    // ---- helpers ----

    @Test
    void sha256Hex_matchesKnownVector() {
        assertThat(AuthService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void normalize_trimsAndLowerCases() {
        assertThat(AuthService.normalize("  MiXeD_Case  ")).isEqualTo("mixed_case");
    }

    private static ApiException catchApi(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected ApiException");
    }
}
