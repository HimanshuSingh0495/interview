package com.example.app.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** id = an existing option to keep (and maybe rename); null = a new option. */
public record EditOptionDto(
        Long id,

        @NotBlank(message = "Option text is required")
        @Size(max = 100, message = "Option must be 100 characters or fewer")
        String text) {
}
