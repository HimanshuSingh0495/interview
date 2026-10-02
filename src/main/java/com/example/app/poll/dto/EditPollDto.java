package com.example.app.poll.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EditPollDto(
        @NotBlank(message = "Question is required")
        @Size(max = 300, message = "Question must be 300 characters or fewer")
        String question,

        // The version the client loaded; a mismatch means someone else saved in between.
        @NotNull(message = "Version is required")
        Long version,

        @NotNull(message = "Options are required")
        @Size(min = 2, max = 10, message = "A poll needs 2-10 options")
        List<@Valid @NotNull(message = "Option is required") EditOptionDto> options) {
}
