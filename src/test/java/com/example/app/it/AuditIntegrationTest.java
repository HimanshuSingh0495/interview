package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class AuditIntegrationTest extends ApiTestSupport {

    record Row(Long actorId, String action, Long pollId, String details) {
    }

    @Test
    void everyActionIsAuditedInOrder() throws Exception {
        TestUser alice = register("alice");
        TestUser bob = register("bob");
        JsonNode poll = createPoll(alice, "Audit me", "A", "B");
        long id = poll.get("id").asLong();
        long a = optionId(poll, 0);
        long b = optionId(poll, 1);
        expect(put("/api/v1/polls/" + id), alice, editBody("Audit me!", 0, a, "A", b, "B", null, "C"), 200);
        vote(bob, id, a, 201);
        changeVote(bob, id, b, 200);

        // rejected actions must not leave audit rows
        vote(bob, id, a, 409);
        expect(put("/api/v1/polls/" + id), alice, editBody("stale", 0, a, "A", b, "B"), 409);
        changeVote(bob, id, b, 200); // same option: no-op, no audit row

        List<Row> rows = jdbc.query(
                "SELECT actor_id, action, poll_id, details FROM audit_event WHERE actor_id IN (?, ?) ORDER BY id",
                (rs, i) -> new Row(rs.getObject("actor_id", Long.class), rs.getString("action"),
                        rs.getObject("poll_id", Long.class), rs.getString("details")),
                alice.id(), bob.id());

        assertThat(rows).extracting(Row::action).containsExactly(
                "USER_REGISTERED", "USER_REGISTERED", "POLL_CREATED", "POLL_UPDATED", "VOTE_CAST", "VOTE_CHANGED");
        assertThat(rows).extracting(Row::actorId).containsExactly(
                alice.id(), bob.id(), alice.id(), alice.id(), bob.id(), bob.id());
        assertThat(rows).extracting(Row::pollId).containsExactly(null, null, id, id, id, id);

        assertThat(rows.get(0).details()).contains(alice.username());
        assertThat(rows.get(4).details()).contains(String.valueOf(a));
        String changed = rows.get(5).details();
        assertThat(changed).contains(a + " -> " + b);
        assertThat(changed.indexOf(String.valueOf(a))).isLessThan(changed.indexOf(" -> " + b));

        // per-poll history through the same table
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE poll_id = ?", Integer.class, id))
                .isEqualTo(4);
        // login is not an audited action
        expect(post("/api/v1/auth/login"), null,
                Map.of("username", alice.username(), "password", PASSWORD), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id = ?", Integer.class,
                alice.id())).isEqualTo(3);
    }
}
