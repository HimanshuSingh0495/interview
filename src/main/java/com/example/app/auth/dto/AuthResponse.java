package com.example.app.auth.dto;

public record AuthResponse(String token, Long userId, String username) {
}
