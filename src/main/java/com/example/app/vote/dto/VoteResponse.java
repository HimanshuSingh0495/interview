package com.example.app.vote.dto;

import java.time.Instant;

public record VoteResponse(Long pollId, Long optionId, Instant updatedAt) {
}
