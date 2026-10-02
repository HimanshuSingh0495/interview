package com.example.app.auth;

/** The authenticated user, set as the Spring Security principal by {@link TokenAuthFilter}. */
public record CurrentUser(Long id, String username) {
}
