package com.example.app.poll.dto;

public record OptionResponse(Long optionId, String text, int voteCount) {
}
