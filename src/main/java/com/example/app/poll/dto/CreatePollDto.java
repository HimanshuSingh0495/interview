package com.example.app.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePollDto(
        @NotBlank(message = "Question is required")
        @Size(max = 300, message = "Question must be 300 characters or fewer")
        String question,

        @NotNull(message = "Options are required")
        @Size(min = 2, max = 10, message = "A poll needs 2-10 options")
        List<@NotBlank(message = "Option text is required")
             @Size(max = 100, message = "Option must be 100 characters or fewer") String> options) {
}
