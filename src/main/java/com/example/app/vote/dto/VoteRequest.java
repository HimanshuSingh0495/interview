package com.example.app.vote.dto;

import jakarta.validation.constraints.NotNull;

public record VoteRequest(@NotNull(message = "Pick an option") Long optionId) {
}
