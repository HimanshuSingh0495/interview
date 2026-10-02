package com.example.app.vote;

import com.example.app.auth.CurrentUser;
import com.example.app.vote.dto.VoteRequest;
import com.example.app.vote.dto.VoteResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/polls/{pollId}/votes")
public class VoteController {

    private final VoteService voteService;

    public VoteController(VoteService voteService) {
        this.voteService = voteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoteResponse cast(@PathVariable Long pollId, @Valid @RequestBody VoteRequest request,
                             @AuthenticationPrincipal CurrentUser me) {
        return voteService.cast(pollId, request.optionId(), me);
    }

    @PutMapping
    public VoteResponse change(@PathVariable Long pollId, @Valid @RequestBody VoteRequest request,
                               @AuthenticationPrincipal CurrentUser me) {
        return voteService.change(pollId, request.optionId(), me);
    }

    @GetMapping("/me")
    public VoteResponse mine(@PathVariable Long pollId, @AuthenticationPrincipal CurrentUser me) {
        return voteService.mine(pollId, me);
    }
}
