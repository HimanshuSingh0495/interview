package com.example.app.vote;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.CurrentUser;
import com.example.app.poll.Poll;
import com.example.app.poll.PollOptionRepository;
import com.example.app.poll.PollRepository;
import com.example.app.poll.PollStatus;
import com.example.app.user.UserRepository;
import com.example.app.vote.dto.VoteResponse;
import com.example.app.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Casting and changing votes without losing any (PLAN.md section 4).
 * Counters only change through SQL arithmetic; the vote table is the source of truth.
 * The bulk counter updates clear the persistence context, so entities are loaded or referenced after them.
 */
@Service
public class VoteService {

    static final String POLL_NOT_FOUND = "Poll not found";
    static final String OPTION_NOT_FOUND = "Option not found in this poll";
    static final String OPTION_GONE = "Option no longer exists";
    static final String POLL_CLOSED = "This poll is closed";
    static final String ALREADY_VOTED = "You already voted on this poll; use PUT to change your vote";
    static final String NOT_VOTED = "You have not voted on this poll yet";

    private final VoteRepository votes;
    private final PollRepository polls;
    private final PollOptionRepository options;
    private final UserRepository users;
    private final AuditService audit;

    public VoteService(VoteRepository votes, PollRepository polls, PollOptionRepository options,
                       UserRepository users, AuditService audit) {
        this.votes = votes;
        this.polls = polls;
        this.options = options;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public VoteResponse cast(Long pollId, Long optionId, CurrentUser me) {
        requireOpen(pollId);
        requireOptionInPoll(pollId, optionId);

        // Increment first: the UPDATE row-locks the option until commit, so a concurrent
        // deleteIfNoVotes waits and then sees vote_count > 0 (and the edit is rejected instead of the vote).
        if (options.incrementVoteCount(pollId, optionId) == 0) {
            throw ApiException.conflict(OPTION_GONE);
        }
        Vote vote;
        try {
            // References only: the context was just cleared and the INSERT needs nothing but the ids.
            vote = votes.saveAndFlush(new Vote(polls.getReferenceById(pollId), users.getReferenceById(me.id()),
                    options.getReferenceById(optionId)));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(user_id, poll_id): double submit or two tabs. The increment above rolls back too.
            throw ApiException.conflict(ALREADY_VOTED);
        }
        audit.record(me.id(), AuditAction.VOTE_CAST, pollId, "option=" + optionId);
        return new VoteResponse(pollId, optionId, vote.getUpdatedAt());
    }

    @Transactional
    public VoteResponse change(Long pollId, Long newOptionId, CurrentUser me) {
        // SELECT ... FOR UPDATE: two changes by the same user run one after the other.
        Vote vote = votes.findForUpdate(me.id(), pollId).orElseThrow(() -> ApiException.notFound(NOT_VOTED));
        Long voteId = vote.getId();
        Long oldOptionId = vote.getOption().getId();
        if (oldOptionId.equals(newOptionId)) {
            return new VoteResponse(pollId, oldOptionId, vote.getUpdatedAt());
        }
        if (vote.getPoll().getStatus() == PollStatus.CLOSED) {
            throw ApiException.conflict(POLL_CLOSED);
        }
        requireOptionInPoll(pollId, newOptionId);

        // Lock the two option rows in ascending id order, so A->B and B->A changes at once cannot deadlock.
        if (oldOptionId < newOptionId) {
            decrement(pollId, oldOptionId);
            increment(pollId, newOptionId);
        } else {
            increment(pollId, newOptionId);
            decrement(pollId, oldOptionId);
        }

        // Re-fetch after the bulk updates cleared the context; the row lock is still ours.
        vote = votes.findById(voteId).orElseThrow(() -> ApiException.notFound(NOT_VOTED));
        vote.changeOption(options.getReferenceById(newOptionId));
        votes.flush();
        audit.record(me.id(), AuditAction.VOTE_CHANGED, pollId, "option " + oldOptionId + " -> " + newOptionId);
        return new VoteResponse(pollId, newOptionId, vote.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public VoteResponse mine(Long pollId, CurrentUser me) {
        Vote vote = votes.findByUserIdAndPollId(me.id(), pollId).orElseThrow(() -> ApiException.notFound(NOT_VOTED));
        return new VoteResponse(pollId, vote.getOption().getId(), vote.getUpdatedAt());
    }

    private void increment(Long pollId, Long optionId) {
        if (options.incrementVoteCount(pollId, optionId) == 0) {
            throw ApiException.conflict(OPTION_GONE);
        }
    }

    private void decrement(Long pollId, Long optionId) {
        // 0 rows would mean the counter is already 0 while a vote row points at it: never silently accept that.
        if (options.decrementVoteCount(pollId, optionId) == 0) {
            throw new IllegalStateException("vote_count out of sync for option " + optionId);
        }
    }

    private void requireOpen(Long pollId) {
        Poll poll = polls.findById(pollId).orElseThrow(() -> ApiException.notFound(POLL_NOT_FOUND));
        if (poll.getStatus() == PollStatus.CLOSED) {
            throw ApiException.conflict(POLL_CLOSED);
        }
    }

    private void requireOptionInPoll(Long pollId, Long optionId) {
        boolean inPoll = options.findById(optionId)
                .map(o -> Objects.equals(o.getPoll().getId(), pollId))
                .orElse(false);
        if (!inPoll) {
            throw ApiException.notFound(OPTION_NOT_FOUND);
        }
    }
}
