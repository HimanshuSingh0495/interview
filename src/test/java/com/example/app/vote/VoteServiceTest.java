package com.example.app.vote;

import com.example.app.audit.AuditAction;
import com.example.app.audit.AuditService;
import com.example.app.auth.CurrentUser;
import com.example.app.poll.Poll;
import com.example.app.poll.PollOption;
import com.example.app.poll.PollOptionRepository;
import com.example.app.poll.PollRepository;
import com.example.app.poll.PollStatus;
import com.example.app.user.User;
import com.example.app.user.UserRepository;
import com.example.app.vote.dto.VoteResponse;
import com.example.app.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    private static final long POLL_ID = 7L;
    private static final long RED = 11L;
    private static final long GREEN = 12L;
    private static final long OTHER_POLL_OPTION = 21L;
    private static final CurrentUser BOB = new CurrentUser(2L, "bob");

    @Mock VoteRepository votes;
    @Mock PollRepository polls;
    @Mock PollOptionRepository options;
    @Mock UserRepository users;
    @Mock AuditService audit;

    VoteService service;
    User creator;
    User bob;
    Poll poll;
    PollOption red;
    PollOption green;
    PollOption otherPollOption;

    @BeforeEach
    void setUp() {
        service = new VoteService(votes, polls, options, users, audit);
        creator = user(1L, "alice");
        bob = user(BOB.id(), "bob");

        poll = new Poll(creator, "share12345", "Best colour?");
        setField(poll, "id", POLL_ID);
        red = poll.addOption("Red", 0);
        setField(red, "id", RED);
        green = poll.addOption("Green", 1);
        setField(green, "id", GREEN);

        Poll other = new Poll(creator, "other12345", "Other?");
        setField(other, "id", 8L);
        otherPollOption = other.addOption("Elsewhere", 0);
        setField(otherPollOption, "id", OTHER_POLL_OPTION);

        lenient().when(options.findById(RED)).thenReturn(Optional.of(red));
        lenient().when(options.findById(GREEN)).thenReturn(Optional.of(green));
        lenient().when(options.findById(OTHER_POLL_OPTION)).thenReturn(Optional.of(otherPollOption));
        lenient().when(options.findById(99L)).thenReturn(Optional.empty());
    }

    private static User user(long id, String name) {
        User u = new User(name, "hash");
        setField(u, "id", id);
        return u;
    }

    private void close() {
        setField(poll, "status", PollStatus.CLOSED);
    }

    private static void assertApi(Executable call, HttpStatus status, String message) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    // ================= cast =================

    @Nested
    class Cast {

        @BeforeEach
        void stubs() {
            lenient().when(polls.findById(POLL_ID)).thenReturn(Optional.of(poll));
            lenient().when(polls.getReferenceById(POLL_ID)).thenReturn(poll);
            lenient().when(users.getReferenceById(BOB.id())).thenReturn(bob);
            lenient().when(options.getReferenceById(RED)).thenReturn(red);
            lenient().when(votes.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void success_incrementsThenInsertsAndAudits() {
            when(options.incrementVoteCount(POLL_ID, RED)).thenReturn(1);

            VoteResponse res = service.cast(POLL_ID, RED, BOB);

            assertThat(res.pollId()).isEqualTo(POLL_ID);
            assertThat(res.optionId()).isEqualTo(RED);
            assertThat(res.updatedAt()).isNotNull();

            ArgumentCaptor<Vote> saved = ArgumentCaptor.forClass(Vote.class);
            InOrder order = inOrder(options, votes, audit);
            order.verify(options).incrementVoteCount(POLL_ID, RED);
            order.verify(votes).saveAndFlush(saved.capture());
            order.verify(audit).record(BOB.id(), AuditAction.VOTE_CAST, POLL_ID, "option=11");
            assertThat(saved.getValue().getPoll()).isSameAs(poll);
            assertThat(saved.getValue().getUser()).isSameAs(bob);
            assertThat(saved.getValue().getOption()).isSameAs(red);
        }

        @Test
        void missingPoll_is404() {
            when(polls.findById(POLL_ID)).thenReturn(Optional.empty());

            assertApi(() -> service.cast(POLL_ID, RED, BOB), HttpStatus.NOT_FOUND, "Poll not found");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
        }

        @Test
        void closedPoll_is409() {
            close();

            assertApi(() -> service.cast(POLL_ID, RED, BOB), HttpStatus.CONFLICT, "This poll is closed");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verify(votes, never()).saveAndFlush(any());
            verifyNoInteractions(audit);
        }

        @Test
        void missingOption_is404() {
            assertApi(() -> service.cast(POLL_ID, 99L, BOB), HttpStatus.NOT_FOUND, "Option not found in this poll");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verifyNoInteractions(audit);
        }

        @Test
        void optionFromAnotherPoll_is404() {
            assertApi(() -> service.cast(POLL_ID, OTHER_POLL_OPTION, BOB),
                    HttpStatus.NOT_FOUND, "Option not found in this poll");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
        }

        @Test
        void incrementAffectingNoRows_is409() {
            when(options.incrementVoteCount(POLL_ID, RED)).thenReturn(0);

            assertApi(() -> service.cast(POLL_ID, RED, BOB), HttpStatus.CONFLICT, "Option no longer exists");
            verify(votes, never()).saveAndFlush(any());
            verifyNoInteractions(audit);
        }

        @Test
        void alreadyVoted_is409() {
            when(options.incrementVoteCount(POLL_ID, RED)).thenReturn(1);
            when(votes.saveAndFlush(any(Vote.class))).thenThrow(new DataIntegrityViolationException("uk_vote_user_poll"));

            assertApi(() -> service.cast(POLL_ID, RED, BOB),
                    HttpStatus.CONFLICT, "You already voted on this poll; use PUT to change your vote");
            verifyNoInteractions(audit);
        }
    }

    // ================= change =================

    @Nested
    class Change {

        Vote vote;

        @BeforeEach
        void stubs() {
            vote = new Vote(poll, bob, red);
            setField(vote, "id", 500L);
            lenient().when(votes.findForUpdate(BOB.id(), POLL_ID)).thenReturn(Optional.of(vote));
            lenient().when(votes.findById(500L)).thenReturn(Optional.of(vote));
            lenient().when(options.getReferenceById(GREEN)).thenReturn(green);
            lenient().when(options.getReferenceById(RED)).thenReturn(red);
        }

        @Test
        void noExistingVote_is404() {
            when(votes.findForUpdate(BOB.id(), POLL_ID)).thenReturn(Optional.empty());

            assertApi(() -> service.change(POLL_ID, GREEN, BOB),
                    HttpStatus.NOT_FOUND, "You have not voted on this poll yet");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verify(options, never()).decrementVoteCount(anyLong(), anyLong());
        }

        @Test
        void sameOption_isNoOp() {
            VoteResponse res = service.change(POLL_ID, RED, BOB);

            assertThat(res.optionId()).isEqualTo(RED);
            assertThat(res.pollId()).isEqualTo(POLL_ID);
            assertThat(res.updatedAt()).isEqualTo(vote.getUpdatedAt());
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verify(options, never()).decrementVoteCount(anyLong(), anyLong());
            verify(votes, never()).flush();
            verifyNoInteractions(audit);
            assertThat(vote.getOption()).isSameAs(red);
        }

        @Test
        void closedPoll_is409() {
            close();

            assertApi(() -> service.change(POLL_ID, GREEN, BOB), HttpStatus.CONFLICT, "This poll is closed");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verify(options, never()).decrementVoteCount(anyLong(), anyLong());
        }

        @Test
        void newOptionNotInPoll_is404() {
            assertApi(() -> service.change(POLL_ID, OTHER_POLL_OPTION, BOB),
                    HttpStatus.NOT_FOUND, "Option not found in this poll");
            verify(options, never()).incrementVoteCount(anyLong(), anyLong());
            verify(options, never()).decrementVoteCount(anyLong(), anyLong());
        }

        @Test
        void differentOption_movesCountersAndUpdatesVote() {
            when(options.decrementVoteCount(POLL_ID, RED)).thenReturn(1);
            when(options.incrementVoteCount(POLL_ID, GREEN)).thenReturn(1);

            VoteResponse res = service.change(POLL_ID, GREEN, BOB);

            // Lower id first: decrement(11) then increment(12).
            InOrder order = inOrder(options, votes, audit);
            order.verify(options).decrementVoteCount(POLL_ID, RED);
            order.verify(options).incrementVoteCount(POLL_ID, GREEN);
            order.verify(votes).findById(500L);
            order.verify(votes).flush();
            order.verify(audit).record(BOB.id(), AuditAction.VOTE_CHANGED, POLL_ID, "option 11 -> 12");
            assertThat(vote.getOption()).isSameAs(green);
            assertThat(res.optionId()).isEqualTo(GREEN);
            verify(options, never()).incrementVoteCount(POLL_ID, RED);
            verify(options, never()).decrementVoteCount(POLL_ID, GREEN);
        }

        @Test
        void differentOption_higherToLower_locksInAscendingIdOrder() {
            vote.changeOption(green); // current vote: 12
            when(options.incrementVoteCount(POLL_ID, RED)).thenReturn(1);
            when(options.decrementVoteCount(POLL_ID, GREEN)).thenReturn(1);

            service.change(POLL_ID, RED, BOB);

            InOrder order = inOrder(options, audit);
            order.verify(options).incrementVoteCount(POLL_ID, RED);
            order.verify(options).decrementVoteCount(POLL_ID, GREEN);
            order.verify(audit).record(BOB.id(), AuditAction.VOTE_CHANGED, POLL_ID, "option 12 -> 11");
            assertThat(vote.getOption()).isSameAs(red);
        }

        @Test
        void incrementAffectingNoRows_is409() {
            when(options.decrementVoteCount(POLL_ID, RED)).thenReturn(1);
            when(options.incrementVoteCount(POLL_ID, GREEN)).thenReturn(0);

            assertApi(() -> service.change(POLL_ID, GREEN, BOB), HttpStatus.CONFLICT, "Option no longer exists");
            verifyNoInteractions(audit);
            assertThat(vote.getOption()).isSameAs(red);
        }

        @Test
        void decrementAffectingNoRows_isAnInternalError() {
            when(options.decrementVoteCount(POLL_ID, RED)).thenReturn(0);

            assertThatThrownBy(() -> service.change(POLL_ID, GREEN, BOB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("vote_count out of sync for option 11");
            verifyNoInteractions(audit);
        }
    }

    // ================= mine =================

    @Nested
    class Mine {

        @Test
        void notVoted_is404() {
            when(votes.findByUserIdAndPollId(BOB.id(), POLL_ID)).thenReturn(Optional.empty());

            assertApi(() -> service.mine(POLL_ID, BOB), HttpStatus.NOT_FOUND, "You have not voted on this poll yet");
        }

        @Test
        void voted_returnsCurrentOption() {
            Vote vote = new Vote(poll, bob, green);
            when(votes.findByUserIdAndPollId(BOB.id(), POLL_ID)).thenReturn(Optional.of(vote));

            VoteResponse res = service.mine(POLL_ID, BOB);

            assertThat(res).isEqualTo(new VoteResponse(POLL_ID, GREEN, vote.getUpdatedAt()));
        }
    }
}
