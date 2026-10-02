package com.example.app.auth;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    @EntityGraph(attributePaths = "user") // load the user in the same query
    Optional<AuthToken> findByTokenHash(String tokenHash);
}
