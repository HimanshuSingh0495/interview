package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class VoteApiIntegrationTest extends ApiTestSupport {

    @Test
    void castReturns201AndSecondCastIs409() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);

        JsonNode cast = vote(bob, id, a, 201);
        assertThat(cast.get("pollId").asLong()).isEqualTo(id);
        assertThat(cast.get("optionId").asLong()).isEqualTo(a);
        assertThat(cast.get("updatedAt").asText()).isNotBlank();

        assertError(vote(bob, id, b, 409), 409);
        assertError(vote(bob, id, a, 409), 409);

        // the rejected casts were rolled back completely
        assertThat(dbVoteCount(a)).isEqualTo(1);
        assertThat(dbVoteCount(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE poll_id = ?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void changeMovesTheVoteAndTheCounts() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        TestUser carol = register("carol");
        JsonNode poll = createPoll(alice, "Q", "A", "B", "C");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);
        long c = optionId(poll, 2);
        vote(bob, id, a, 201);
        vote(carol, id, a, 201);

        JsonNode changed = changeVote(bob, id, c, 200);
        assertThat(changed.get("optionId").asLong()).isEqualTo(c);

        JsonNode after = getPoll(alice, id);
        assertThat(after.get("options").get(0).get("voteCount").asInt()).isEqualTo(1);
        assertThat(after.get("options").get(1).get("voteCount").asInt()).isZero();
        assertThat(after.get("options").get(2).get("voteCount").asInt()).isEqualTo(1);
        assertThat(after.get("totalVotes").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT option_id FROM vote WHERE poll_id = ? AND user_id = ?", Long.class,
                id, bob.id())).isEqualTo(c);

        // choosing the same option again is a no-op
        assertThat(changeVote(bob, id, c, 200).get("optionId").asLong()).isEqualTo(c);
        assertThat(dbVoteCount(c)).isEqualTo(1);

        // back and forth keeps counts in sync with the vote rows
        changeVote(bob, id, b, 200);
        changeVote(bob, id, a, 200);
        for (long opt : new long[]{a, b, c}) {
            assertThat((long) dbVoteCount(opt)).isEqualTo(dbVoteRows(opt));
        }
        assertThat(dbVoteCount(a)).isEqualTo(2);
    }

    @Test
    void changeBeforeVotingIs404() throws Exception {
        TestUser alice = register("alice");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        assertError(changeVote(register("bob"), poll.get("id").asLong(), optionId(poll, 0), 404), 404);
        assertThat(dbVoteCount(optionId(poll, 0))).isZero();
    }

    @Test
    void votesMeReturns200Or404() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        long id = poll.get("id").asLong();

        assertError(expect(get("/api/v1/polls/" + id + "/votes/me"), bob, null, 404), 404);
        vote(bob, id, optionId(poll, 1), 201);
        JsonNode mine = expect(get("/api/v1/polls/" + id + "/votes/me"), bob, null, 200);
        assertThat(mine.get("pollId").asLong()).isEqualTo(id);
        assertThat(mine.get("optionId").asLong()).isEqualTo(optionId(poll, 1));
        assertError(expect(get("/api/v1/polls/" + id + "/votes/me"), alice, null, 404), 404);
    }

    @Test
    void optionFromAnotherPollIs404() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        JsonNode other = createPoll(alice, "Other", "X", "Y");
        long id = poll.get("id").asLong();

        assertError(vote(bob, id, optionId(other, 0), 404), 404);
        assertError(vote(bob, id, 999_999_999L, 404), 404);
        assertThat(dbVoteCount(optionId(other, 0))).isZero();

        vote(bob, id, optionId(poll, 0), 201);
        assertError(changeVote(bob, id, optionId(other, 0), 404), 404);
        assertThat(dbVoteCount(optionId(poll, 0))).isEqualTo(1);
        assertThat(dbVoteCount(optionId(other, 0))).isZero();
    }

    @Test
    void missingPollAndBadBodies() throws Exception {
        TestUser bob = register("bob");
        assertError(vote(bob, 999_999_999L, 1L, 404), 404);
        JsonNode poll = createPoll(register("alice"), "Q", "A", "B");
        JsonNode body = expect(post("/api/v1/polls/" + poll.get("id").asLong() + "/votes"), bob, Map.of(), 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("optionId");
        assertError(expect(post("/api/v1/polls/" + poll.get("id").asLong() + "/votes"), null,
                Map.of("optionId", optionId(poll, 0)), 401), 401);
    }

    @Test
    void closedPollRejectsCastAndChangeWith409() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        TestUser carol = register("carol");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);
        vote(bob, id, a, 201);

        assertThat(jdbc.update("UPDATE poll SET status = 'CLOSED' WHERE id = ?", id)).isEqualTo(1);

        assertError(vote(carol, id, a, 409), 409);
        assertError(changeVote(bob, id, b, 409), 409);
        assertThat(dbVoteCount(a)).isEqualTo(1);
        assertThat(dbVoteCount(b)).isZero();
        assertThat(expect(get("/api/v1/polls/share/" + poll.get("shareId").asText()), null, null, 200)
                .get("status").asText()).isEqualTo("CLOSED");
    }
}
