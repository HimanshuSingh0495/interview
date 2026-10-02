package com.example.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationDto(
        // Surrounding spaces are allowed here because the service trims before storing.
        @NotBlank(message = "Username is required")
        @Pattern(regexp = "\\s*[A-Za-z0-9_]{3,30}\\s*",
                message = "Username must be 3-30 letters, digits or underscores")
        String username,

        @NotNull(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        String password) {
}
