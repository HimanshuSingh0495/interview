package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AuthApiIntegrationTest extends ApiTestSupport {

    @Test
    void registerReturns201WithTokenAndLowerCasedUsername() throws Exception {
        String name = uniqueName("MixedCase");
        JsonNode body = expect(post("/api/v1/auth/register"), null,
                Map.of("username", "  " + name + " ", "password", PASSWORD), 201);

        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("userId").asLong()).isPositive();
        assertThat(body.get("username").asText()).isEqualTo(name.toLowerCase());

        // The token works on a protected endpoint.
        TestUser user = new TestUser(body.get("token").asText(), body.get("userId").asLong(), name.toLowerCase());
        expect(get("/api/v1/users/me/polls"), user, null, 200);
    }

    @Test
    void duplicateUsernameIs409EvenWithDifferentCase() throws Exception {
        TestUser user = register("dup");
        JsonNode body = expect(post("/api/v1/auth/register"), null,
                Map.of("username", user.username().toUpperCase(), "password", PASSWORD), 409);
        assertError(body, 409);
    }

    @Test
    void invalidRegistrationIs400WithFieldErrors() throws Exception {
        JsonNode body = expect(post("/api/v1/auth/register"), null,
                Map.of("username", "a!", "password", "short"), 400);
        assertError(body, 400);
        List<String> fields = new ArrayList<>();
        body.get("fieldErrors").forEach(f -> {
            fields.add(f.get("field").asText());
            assertThat(f.get("message").asText()).isNotBlank();
        });
        assertThat(fields).containsExactlyInAnyOrder("username", "password");
    }

    @Test
    void loginSucceedsCaseInsensitivelyAndWrongPasswordIs401() throws Exception {
        TestUser user = register("login");

        JsonNode ok = expect(post("/api/v1/auth/login"), null,
                Map.of("username", user.username().toUpperCase(), "password", PASSWORD), 200);
        assertThat(ok.get("token").asText()).isNotBlank().isNotEqualTo(user.token());
        assertThat(ok.get("userId").asLong()).isEqualTo(user.id());
        assertThat(ok.get("username").asText()).isEqualTo(user.username());

        assertError(expect(post("/api/v1/auth/login"), null,
                Map.of("username", user.username(), "password", "wrong-password"), 401), 401);
        assertError(expect(post("/api/v1/auth/login"), null,
                Map.of("username", uniqueName("nobody"), "password", PASSWORD), 401), 401);
    }

    @Test
    void protectedEndpointWithoutTokenIs401Json() throws Exception {
        JsonNode body = expect(get("/api/v1/users/me/polls"), null, null, 401);
        assertError(body, 401);
        assertThat(body.get("error").asText()).isEqualTo("Unauthorized");
    }

    @Test
    void garbageTokenIs401Json() throws Exception {
        JsonNode body = expect(get("/api/v1/users/me/polls")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"), null, null, 401);
        assertError(body, 401);
        // A non-bearer scheme is ignored too.
        assertError(expect(get("/api/v1/users/me/polls")
                .header(HttpHeaders.AUTHORIZATION, "Basic Zm9vOmJhcg=="), null, null, 401), 401);
    }

    @Test
    void expiredTokenIs401Json() throws Exception {
        TestUser user = register("expired");
        expect(get("/api/v1/users/me/polls"), user, null, 200);

        int updated = jdbc.update("UPDATE auth_token SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), user.id());
        assertThat(updated).isEqualTo(1);

        assertError(expect(get("/api/v1/users/me/polls"), user, null, 401), 401);
    }

    @Test
    void passwordIsStoredAsBcryptHash() throws Exception {
        TestUser user = register("hash");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, user.id());
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD).hasSize(60);
        // Only the SHA-256 hex of the token is stored, never the raw token.
        String tokenHash = jdbc.queryForObject("SELECT token_hash FROM auth_token WHERE user_id = ?",
                String.class, user.id());
        assertThat(tokenHash).hasSize(64).isNotEqualTo(user.token());
    }
}
