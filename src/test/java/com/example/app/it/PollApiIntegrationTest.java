package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class PollApiIntegrationTest extends ApiTestSupport {

    @Test
    void createReturns201WithFullPollResponse() throws Exception {
        TestUser alice = register("alice");
        JsonNode poll = createPoll(alice, "  Best colour?  ", " Red ", "Green", "Blue");

        assertThat(poll.get("id").asLong()).isPositive();
        assertThat(poll.get("shareId").asText()).isNotBlank();
        assertThat(poll.get("question").asText()).isEqualTo("Best colour?");
        assertThat(poll.get("status").asText()).isEqualTo("OPEN");
        assertThat(poll.get("version").asLong()).isEqualTo(0);
        assertThat(poll.get("creatorUsername").asText()).isEqualTo(alice.username());
        assertThat(poll.get("createdAt").asText()).isNotBlank();
        assertThat(poll.get("updatedAt").asText()).isNotBlank();
        assertThat(poll.get("totalVotes").asInt()).isZero();
        List<String> texts = new ArrayList<>();
        poll.get("options").forEach(o -> {
            texts.add(o.get("text").asText());
            assertThat(o.get("optionId").asLong()).isPositive();
            assertThat(o.get("voteCount").asInt()).isZero();
        });
        assertThat(texts).containsExactly("Red", "Green", "Blue");

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM poll_option WHERE poll_id = ?", Integer.class,
                poll.get("id").asLong());
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void createValidationErrors() throws Exception {
        TestUser alice = register("alice");

        // too few options: bean validation -> fieldErrors
        JsonNode tooFew = expect(post("/api/v1/polls"), alice, Map.of("question", "Q", "options", List.of("A")), 400);
        assertError(tooFew, 400);
        assertThat(tooFew.get("fieldErrors").get(0).get("field").asText()).isEqualTo("options");

        // too many options
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("Option " + i);
        }
        assertThat(expect(post("/api/v1/polls"), alice, Map.of("question", "Q", "options", eleven), 400)
                .get("fieldErrors").get(0).get("field").asText()).isEqualTo("options");

        // duplicates (case-insensitive, after trimming)
        assertError(expect(post("/api/v1/polls"), alice,
                Map.of("question", "Q", "options", List.of("Yes", " yes ")), 400), 400);

        // question too long
        assertThat(expect(post("/api/v1/polls"), alice,
                Map.of("question", "q".repeat(301), "options", List.of("A", "B")), 400)
                .get("fieldErrors").get(0).get("field").asText()).isEqualTo("question");

        // option too long
        assertThat(expect(post("/api/v1/polls"), alice,
                Map.of("question", "Q", "options", List.of("A", "b".repeat(101))), 400)
                .get("fieldErrors").get(0).get("field").asText()).startsWith("options");

        // blank question
        assertThat(expect(post("/api/v1/polls"), alice,
                Map.of("question", "   ", "options", List.of("A", "B")), 400)
                .get("fieldErrors").get(0).get("field").asText()).isEqualTo("question");

        // boundaries are accepted
        createPoll(alice, "q".repeat(300), "a".repeat(100), "B");

        Integer polls = jdbc.queryForObject("SELECT COUNT(*) FROM poll WHERE creator_id = ?", Integer.class, alice.id());
        assertThat(polls).isEqualTo(1);
    }

    @Test
    void getByIdIsCreatorOnly() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        long id = createPoll(alice, "Q?", "A", "B").get("id").asLong();

        assertThat(getPoll(alice, id).get("question").asText()).isEqualTo("Q?");
        assertError(expect(get("/api/v1/polls/" + id), bob, null, 403), 403);
        assertError(expect(get("/api/v1/polls/999999999"), alice, null, 404), 404);
        assertError(expect(get("/api/v1/polls/" + id), null, null, 401), 401);
    }

    @Test
    void shareEndpointIsPublic() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Lunch?", "Pizza", "Sushi");
        vote(bob, poll.get("id").asLong(), optionId(poll, 1), 201);

        JsonNode shared = expect(get("/api/v1/polls/share/" + poll.get("shareId").asText()), null, null, 200);
        assertThat(shared.get("id").asLong()).isEqualTo(poll.get("id").asLong());
        assertThat(shared.get("question").asText()).isEqualTo("Lunch?");
        assertThat(shared.get("totalVotes").asInt()).isEqualTo(1);
        assertThat(shared.get("options").get(1).get("voteCount").asInt()).isEqualTo(1);

        assertError(expect(get("/api/v1/polls/share/nosuchshare"), null, null, 404), 404);
    }

    @Test
    void myPollsReturnsOnlyCallersPollsNewestFirst() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        List<Long> aliceIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            aliceIds.add(createPoll(alice, "Alice " + i, "A", "B").get("id").asLong());
            Thread.sleep(5); // distinct created_at
        }
        long bobPoll = createPoll(bob, "Bob's", "A", "B").get("id").asLong();

        JsonNode mine = expect(get("/api/v1/users/me/polls"), alice, null, 200);
        List<Long> ids = new ArrayList<>();
        mine.forEach(p -> {
            ids.add(p.get("id").asLong());
            assertThat(p.get("creatorUsername").asText()).isEqualTo(alice.username());
        });
        Collections.reverse(aliceIds);
        assertThat(ids).containsExactlyElementsOf(aliceIds).doesNotContain(bobPoll);

        TestUser carol = register("carol");
        assertThat(expect(get("/api/v1/users/me/polls"), carol, null, 200).size()).isZero();
    }

    @Test
    void editRenameAddAndRemoveUnvotedOption() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Pets?", "Cat", "Dog", "Fish");
        long id = poll.get("id").asLong();
        long cat = optionId(poll, 0);
        long dog = optionId(poll, 1);
        long fish = optionId(poll, 2);
        vote(bob, id, cat, 201);

        // rename Cat (has a vote) and the question, drop Fish (0 votes), add Bird
        JsonNode edited = expect(put("/api/v1/polls/" + id), alice,
                editBody("Favourite pet?", 0, cat, "Kitten", dog, "Dog", null, "Bird"), 200);

        assertThat(edited.get("question").asText()).isEqualTo("Favourite pet?");
        assertThat(edited.get("version").asLong()).isEqualTo(1);
        JsonNode opts = edited.get("options");
        assertThat(opts.size()).isEqualTo(3);
        assertThat(opts.get(0).get("optionId").asLong()).isEqualTo(cat);
        assertThat(opts.get(0).get("text").asText()).isEqualTo("Kitten");
        assertThat(opts.get(0).get("voteCount").asInt()).isEqualTo(1);
        assertThat(opts.get(1).get("optionId").asLong()).isEqualTo(dog);
        assertThat(opts.get(2).get("text").asText()).isEqualTo("Bird");
        assertThat(opts.get(2).get("optionId").asLong()).isPositive();
        assertThat(edited.get("totalVotes").asInt()).isEqualTo(1);

        // the vote survived the rename, and Fish is gone from the DB
        assertThat(dbVoteRows(cat)).isEqualTo(1);
        assertThat(dbVoteCount(cat)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poll_option WHERE id = ?", Integer.class, fish)).isZero();
        assertThat(expect(get("/api/v1/polls/" + id + "/votes/me"), bob, null, 200)
                .get("optionId").asLong()).isEqualTo(cat);

        // re-ordering is persisted too
        JsonNode reordered = expect(put("/api/v1/polls/" + id), alice,
                editBody("Favourite pet?", 1, dog, "Dog", cat, "Kitten", opts.get(2).get("optionId").asLong(), "Bird"),
                200);
        assertThat(reordered.get("options").get(0).get("optionId").asLong()).isEqualTo(dog);
        assertThat(reordered.get("version").asLong()).isEqualTo(2);
    }

    @Test
    void editRemovingOptionWithVotesIs409AndNothingChanges() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Tea or coffee?", "Tea", "Coffee", "Water");
        long id = poll.get("id").asLong();
        long tea = optionId(poll, 0);
        long coffee = optionId(poll, 1);
        long water = optionId(poll, 2);
        vote(bob, id, tea, 201);

        JsonNode conflict = expect(put("/api/v1/polls/" + id), alice,
                editBody("Changed question", 0, coffee, "Coffee!", water, "Water"), 409);
        assertError(conflict, 409);

        // the whole edit rolled back: vote, count, option, question, rename and version untouched
        assertThat(dbVoteRows(tea)).isEqualTo(1);
        assertThat(dbVoteCount(tea)).isEqualTo(1);
        JsonNode after = getPoll(alice, id);
        assertThat(after.get("question").asText()).isEqualTo("Tea or coffee?");
        assertThat(after.get("version").asLong()).isZero();
        assertThat(after.get("options").size()).isEqualTo(3);
        assertThat(after.get("options").get(1).get("text").asText()).isEqualTo("Coffee");
    }

    @Test
    void editWithStaleVersionIs409AndVersionIncrementsOnEachEdit() throws Exception {
        TestUser alice = register("alice");
        JsonNode poll = createPoll(alice, "V0", "A", "B");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);

        for (int v = 0; v < 3; v++) {
            JsonNode edited = expect(put("/api/v1/polls/" + id), alice, editBody("V" + (v + 1), v, a, "A", b, "B"), 200);
            assertThat(edited.get("version").asLong()).isEqualTo(v + 1);
        }
        // options-only edit also bumps the version
        assertThat(expect(put("/api/v1/polls/" + id), alice, editBody("V3", 3, a, "A2", b, "B"), 200)
                .get("version").asLong()).isEqualTo(4);

        JsonNode stale = expect(put("/api/v1/polls/" + id), alice, editBody("Stale", 1, a, "A", b, "B"), 409);
        assertError(stale, 409);
        JsonNode after = getPoll(alice, id);
        assertThat(after.get("question").asText()).isEqualTo("V3");
        assertThat(after.get("version").asLong()).isEqualTo(4);
    }

    @Test
    void editIsCreatorOnlyAndValidated() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Q", "A", "B");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);

        assertError(expect(put("/api/v1/polls/" + id), bob, editBody("Hijack", 0, a, "A", b, "B"), 403), 403);
        assertError(expect(put("/api/v1/polls/999999999"), alice, editBody("Q", 0, a, "A", b, "B"), 404), 404);
        assertError(expect(put("/api/v1/polls/" + id), alice, editBody("Q", 0, a, "A"), 400), 400);
        assertError(expect(put("/api/v1/polls/" + id), alice, editBody("Q", 0, a, "Same", b, "same"), 400), 400);
        assertThat(getPoll(alice, id).get("version").asLong()).isZero();
    }

    @Test
    void editWithOptionIdFromAnotherPollIs404() throws Exception {
        TestUser alice = register("alice");
        JsonNode mine = createPoll(alice, "Mine", "A", "B");
        JsonNode other = createPoll(register("bob"), "Other", "X", "Y");
        long id = mine.get("id").asLong();

        JsonNode body = expect(put("/api/v1/polls/" + id), alice,
                editBody("Mine", 0, optionId(mine, 0), "A", optionId(other, 0), "X"), 404);
        assertError(body, 404);
        // the foreign option is untouched and still belongs to the other poll
        assertThat(jdbc.queryForObject("SELECT poll_id FROM poll_option WHERE id = ?", Long.class, optionId(other, 0)))
                .isEqualTo(other.get("id").asLong());
        assertThat(getPoll(alice, id).get("options").size()).isEqualTo(2);
    }
}
