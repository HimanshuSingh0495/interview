package com.example.app.poll;

import com.example.app.auth.CurrentUser;
import com.example.app.poll.dto.CreatePollDto;
import com.example.app.poll.dto.EditPollDto;
import com.example.app.poll.dto.PollResponse;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PollController {

    private final PollService pollService;

    public PollController(PollService pollService) {
        this.pollService = pollService;
    }

    @PostMapping("/polls")
    @ResponseStatus(HttpStatus.CREATED)
    public PollResponse create(@Valid @RequestBody CreatePollDto dto, @AuthenticationPrincipal CurrentUser me) {
        return pollService.create(dto, me);
    }

    @GetMapping("/polls/{id}")
    public PollResponse get(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        return pollService.getOwned(id, me);
    }

    @PutMapping("/polls/{id}")
    public PollResponse edit(@PathVariable Long id, @Valid @RequestBody EditPollDto dto,
                             @AuthenticationPrincipal CurrentUser me) {
        return pollService.edit(id, dto, me);
    }

    /** Public: anyone with the link can see the question and results. */
    @GetMapping("/polls/share/{shareId}")
    public PollResponse getByShareId(@PathVariable String shareId) {
        return pollService.getByShareId(shareId);
    }

    @GetMapping("/users/me/polls")
    public List<PollResponse> myPolls(@AuthenticationPrincipal CurrentUser me) {
        return pollService.listMine(me);
    }
}
