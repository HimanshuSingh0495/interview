package com.example.app.poll.dto;

import com.example.app.poll.Poll;
import com.example.app.poll.PollOption;
import com.example.app.poll.PollStatus;
import com.example.app.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class PollResponseTest {

    private static Poll poll() {
        User creator = new User("alice", "hash");
        setField(creator, "id", 1L);
        Poll poll = new Poll(creator, "AbCdEfGh12", "Best colour?");
        setField(poll, "id", 7L);
        setField(poll, "version", 4L);
        return poll;
    }

    private static void option(Poll poll, long id, String text, int position, int votes) {
        PollOption o = poll.addOption(text, position);
        setField(o, "id", id);
        setField(o, "voteCount", votes);
    }

    @Test
    void from_sortsOptionsByPositionAndSumsVotes() {
        Poll poll = poll();
        option(poll, 13L, "Blue", 2, 5);
        option(poll, 11L, "Red", 0, 3);
        option(poll, 12L, "Green", 1, 0);

        PollResponse res = PollResponse.from(poll);

        assertThat(res.options()).containsExactly(
                new OptionResponse(11L, "Red", 3),
                new OptionResponse(12L, "Green", 0),
                new OptionResponse(13L, "Blue", 5));
        assertThat(res.totalVotes()).isEqualTo(8);
    }

    @Test
    void from_copiesPollFields() {
        Poll poll = poll();
        option(poll, 11L, "Red", 0, 1);
        option(poll, 12L, "Green", 1, 1);

        PollResponse res = PollResponse.from(poll);

        assertThat(res.id()).isEqualTo(7L);
        assertThat(res.shareId()).isEqualTo("AbCdEfGh12");
        assertThat(res.question()).isEqualTo("Best colour?");
        assertThat(res.status()).isEqualTo(PollStatus.OPEN);
        assertThat(res.version()).isEqualTo(4L);
        assertThat(res.creatorUsername()).isEqualTo("alice");
        assertThat(res.createdAt()).isEqualTo(poll.getCreatedAt());
        assertThat(res.updatedAt()).isEqualTo(poll.getUpdatedAt());
    }

    @Test
    void from_noVotes_totalIsZero() {
        Poll poll = poll();
        option(poll, 11L, "Red", 0, 0);
        option(poll, 12L, "Green", 1, 0);

        assertThat(PollResponse.from(poll).totalVotes()).isZero();
    }

    @Test
    void from_doesNotReorderTheEntityList() {
        Poll poll = poll();
        option(poll, 12L, "Green", 1, 0);
        option(poll, 11L, "Red", 0, 0);

        PollResponse.from(poll);

        assertThat(poll.getOptions()).extracting(PollOption::getId).containsExactly(12L, 11L);
    }
}
