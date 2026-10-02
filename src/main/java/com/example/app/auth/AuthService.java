package com.example.app.auth;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.dto.AuthResponse;
import com.example.app.auth.dto.LoginDto;
import com.example.app.auth.dto.RegistrationDto;
import com.example.app.user.User;
import com.example.app.user.UserRepository;
import com.example.app.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/** Registration, login and opaque bearer tokens (only their SHA-256 hash is stored). */
@Service
public class AuthService {

    static final String BAD_CREDENTIALS = "Invalid username or password";
    static final String USERNAME_TAKEN = "Username is already taken";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Duration tokenTtl;
    // Hash checked for unknown usernames, so "no such user" takes as long as "wrong password".
    private final String dummyHash;

    public AuthService(UserRepository users, AuthTokenRepository tokens, PasswordEncoder passwordEncoder,
                       AuditService audit, @Value("${app.auth.token-ttl:7d}") Duration tokenTtl) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.tokenTtl = tokenTtl;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public AuthResponse register(RegistrationDto dto) {
        String username = normalize(dto.username());
        // BCrypt only looks at the first 72 bytes; non-ASCII characters can push 72 chars past that.
        if (dto.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("Password must be at most 72 bytes");
        }
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, USERNAME_TAKEN);
        }
        User user;
        try {
            user = users.saveAndFlush(new User(username, passwordEncoder.encode(dto.password())));
        } catch (DataIntegrityViolationException e) {
            // Two registrations for the same name at once: the unique constraint picks the winner.
            throw new ApiException(HttpStatus.CONFLICT, USERNAME_TAKEN);
        }
        audit.record(user.getId(), AuditAction.USER_REGISTERED, null, "username=" + username);
        return new AuthResponse(issueToken(user), user.getId(), user.getUsername());
    }

    @Transactional
    public AuthResponse login(LoginDto dto) {
        Optional<User> found = users.findByUsername(normalize(dto.username()));
        if (found.isEmpty()) {
            passwordEncoder.matches(dto.password(), dummyHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        return new AuthResponse(issueToken(user), user.getId(), user.getUsername());
    }

    /** Returns the user for a raw bearer token, or empty if unknown or expired. */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> authenticate(String rawToken) {
        return tokens.findByTokenHash(sha256Hex(rawToken))
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .map(t -> new CurrentUser(t.getUser().getId(), t.getUser().getUsername()));
    }

    private String issueToken(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new AuthToken(user, sha256Hex(raw), Instant.now().plus(tokenTtl)));
        return raw;
    }

    static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
