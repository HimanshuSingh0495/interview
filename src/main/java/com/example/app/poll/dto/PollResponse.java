package com.example.app.poll.dto;

import com.example.app.poll.Poll;
import com.example.app.poll.PollOption;
import com.example.app.poll.PollStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record PollResponse(Long id, String shareId, String question, PollStatus status, Long version,
                           String creatorUsername, Instant createdAt, Instant updatedAt, int totalVotes,
                           List<OptionResponse> options) {

    /** Call inside a transaction: reads the lazy creator and options. */
    public static PollResponse from(Poll poll) {
        List<OptionResponse> options = poll.getOptions().stream()
                .sorted(Comparator.comparingInt(PollOption::getPosition))
                .map(o -> new OptionResponse(o.getId(), o.getText(), o.getVoteCount()))
                .toList();
        int total = options.stream().mapToInt(OptionResponse::voteCount).sum();
        return new PollResponse(poll.getId(), poll.getShareId(), poll.getQuestion(), poll.getStatus(),
                poll.getVersion(), poll.getCreator().getUsername(), poll.getCreatedAt(), poll.getUpdatedAt(),
                total, options);
    }
}
