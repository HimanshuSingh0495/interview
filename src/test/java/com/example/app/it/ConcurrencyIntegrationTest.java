package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * NF1/NF2 under real concurrency: the app runs on a random port and a real HTTP client fires overlapping
 * requests (all released by one CountDownLatch). The vote table is the source of truth; after every burst
 * each option's vote_count must equal COUNT(vote) for that option.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrency;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate.SQL=warn"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyIntegrationTest {

    private static final int VOTERS = 50;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern CHANGE_DETAILS = Pattern.compile("option (\\d+) -> (\\d+)");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record User(String token, long id) {
    }

    record Resp(int status, JsonNode body) {
    }

    private User creator;
    private final List<User> voters = new ArrayList<>();

    @BeforeAll
    void registerUsers() throws Exception {
        creator = register();
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < VOTERS; i++) {
            tasks.add(() -> registerRaw());
        }
        for (Resp r : runConcurrently(tasks)) {
            assertThat(r.status()).as("register: %s", r.body()).isEqualTo(201);
            voters.add(new User(r.body().get("token").asText(), r.body().get("userId").asLong()));
        }
    }

    // (a) 50 users each fire the same POST twice in parallel: exactly one vote per user survives.
    @Test
    void doubleSubmitCastsCountExactlyOncePerUser() throws Exception {
        JsonNode poll = createPoll("A", "B", "C");
        long pollId = poll.get("id").asLong();
        List<Long> opts = optionIds(poll);

        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < VOTERS; i++) {
            User u = voters.get(i);
            // the two submits pick different options, so a double count would show up in the per-option check
            long first = opts.get(i % 3);
            long second = opts.get((i + 1) % 3);
            tasks.add(() -> send("POST", "/api/v1/polls/" + pollId + "/votes", u, Map.of("optionId", first)));
            tasks.add(() -> send("POST", "/api/v1/polls/" + pollId + "/votes", u, Map.of("optionId", second)));
        }
        List<Resp> results = runConcurrently(tasks);

        assertNoServerErrors(results);
        for (int i = 0; i < VOTERS; i++) {
            List<Integer> pair = List.of(results.get(2 * i).status(), results.get(2 * i + 1).status());
            assertThat(pair).as("user %d statuses", i).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(VOTERS);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(VOTERS);

        assertThat(voteRows(pollId)).isEqualTo(VOTERS);
        assertCountsConsistent(pollId, VOTERS);
        assertThat(auditCount(pollId, "VOTE_CAST")).isEqualTo(VOTERS);
    }

    // (b) Then every user fires several PUTs to random options at once; counters never drift.
    @Test
    void concurrentMixedChangesKeepCountsConsistent() throws Exception {
        JsonNode poll = createPoll("A", "B", "C", "D");
        long pollId = poll.get("id").asLong();
        List<Long> opts = optionIds(poll);
        castAll(pollId, opts);

        Random random = new Random(42);
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (User u : voters) {
            for (int k = 0; k < 4; k++) {
                long target = opts.get(random.nextInt(opts.size()));
                tasks.add(() -> send("PUT", "/api/v1/polls/" + pollId + "/votes", u, Map.of("optionId", target)));
            }
        }
        List<Resp> results = runConcurrently(tasks);

        assertNoServerErrors(results);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as("PUT: %s", r.body()).isEqualTo(200));
        assertThat(voteRows(pollId)).isEqualTo(VOTERS);
        assertCountsConsistent(pollId, VOTERS);
        assertAuditChainsMatchVotes(pollId);
    }

    // (c) Two edits from the same loaded version race: exactly one wins, the other gets 409.
    @Test
    void concurrentEditsWithSameVersionOneWins() throws Exception {
        JsonNode poll = createPoll("A", "B");
        long pollId = poll.get("id").asLong();
        List<Long> opts = optionIds(poll);

        for (int round = 0; round < 15; round++) {
            long version = currentVersion(pollId);
            String q1 = "Round " + round + " left";
            String renameL = "A" + round + "L";
            String renameR = "A" + round + "R";
            String q2 = "Round " + round + " right";
            List<Resp> results = runConcurrently(List.of(
                    () -> send("PUT", "/api/v1/polls/" + pollId, creator,
                            editBody(q1, version, opts.get(0), renameL, opts.get(1), "B")),
                    () -> send("PUT", "/api/v1/polls/" + pollId, creator,
                            editBody(q2, version, opts.get(0), renameR, opts.get(1), "B"))));

            assertNoServerErrors(results);
            assertThat(List.of(results.get(0).status(), results.get(1).status()))
                    .as("round %d: %s", round, results).containsExactlyInAnyOrder(200, 409);
            Resp winner = results.get(0).status() == 200 ? results.get(0) : results.get(1);
            assertThat(winner.body().get("version").asLong()).isEqualTo(version + 1);
            assertThat(currentVersion(pollId)).isEqualTo(version + 1);
            String dbQuestion = jdbc.queryForObject("SELECT question FROM poll WHERE id = ?", String.class, pollId);
            assertThat(dbQuestion).isEqualTo(winner.body().get("question").asText());
            // no mixed state: the option rename belongs to the same winner
            String side = dbQuestion.endsWith("left") ? "L" : "R";
            assertThat(jdbc.queryForObject("SELECT option_text FROM poll_option WHERE id = ?", String.class,
                    opts.get(0))).isEqualTo("A" + round + side);
        }
        assertThat(auditCount(pollId, "POLL_UPDATED")).isEqualTo(15);
    }

    // (d) "remove option X" races "vote for X" (and "change vote to X"): the vote is never lost.
    @Test
    void editRemovingOptionRacingVoteNeverLosesTheVote() throws Exception {
        int voteWon = 0;
        int editWon = 0;
        for (int round = 0; round < 25; round++) {
            JsonNode poll = createPoll("A", "B", "X");
            long pollId = poll.get("id").asLong();
            List<Long> opts = optionIds(poll);
            long x = opts.get(2);
            User voter = voters.get(round % VOTERS);

            List<Resp> results = runConcurrently(List.of(
                    () -> send("PUT", "/api/v1/polls/" + pollId, creator,
                            editBody("Q", 0, opts.get(0), "A", opts.get(1), "B")),
                    () -> send("POST", "/api/v1/polls/" + pollId + "/votes", voter, Map.of("optionId", x))));
            assertNoServerErrors(results);
            int edit = results.get(0).status();
            int vote = results.get(1).status();

            if (vote == 201) {
                voteWon++;
                assertThat(edit).as("round %d: vote won, edit must be rejected: %s", round, results).isEqualTo(409);
                assertThat(optionExists(x)).isTrue();
                assertThat(voteCount(x)).isEqualTo(1);
                assertThat(voteRows(pollId)).isEqualTo(1);
            } else {
                editWon++;
                assertThat(edit).as("round %d: vote rejected, edit must win: %s", round, results).isEqualTo(200);
                assertThat(vote).isIn(404, 409);
                assertThat(optionExists(x)).isFalse();
                assertThat(voteRows(pollId)).isZero();
            }
            assertCountsConsistent(pollId, vote == 201 ? 1 : 0);
        }
        assertThat(voteWon + editWon).isEqualTo(25);

        // Same race with a vote *change* towards X.
        for (int round = 0; round < 25; round++) {
            JsonNode poll = createPoll("A", "B", "X");
            long pollId = poll.get("id").asLong();
            List<Long> opts = optionIds(poll);
            long x = opts.get(2);
            User voter = voters.get(round % VOTERS);
            assertThat(send("POST", "/api/v1/polls/" + pollId + "/votes", voter,
                    Map.of("optionId", opts.get(0))).status()).isEqualTo(201);

            List<Resp> results = runConcurrently(List.of(
                    () -> send("PUT", "/api/v1/polls/" + pollId, creator,
                            editBody("Q", 0, opts.get(0), "A", opts.get(1), "B")),
                    () -> send("PUT", "/api/v1/polls/" + pollId + "/votes", voter, Map.of("optionId", x))));
            assertNoServerErrors(results);
            int edit = results.get(0).status();
            int change = results.get(1).status();
            long votedFor = jdbc.queryForObject("SELECT option_id FROM vote WHERE poll_id = ?", Long.class, pollId);
            if (change == 200) {
                assertThat(edit).as("round %d: %s", round, results).isEqualTo(409);
                assertThat(votedFor).isEqualTo(x);
            } else {
                assertThat(edit).as("round %d: %s", round, results).isEqualTo(200);
                assertThat(change).isIn(404, 409);
                assertThat(votedFor).isEqualTo(opts.get(0)); // old vote kept, not lost
            }
            assertThat(voteRows(pollId)).isEqualTo(1);
            assertCountsConsistent(pollId, 1);
        }
        System.out.printf("edit-vs-vote race: vote won %d, edit won %d%n", voteWon, editWon);
    }

    // (e) Half the users swap A->B while the other half swap B->A, repeatedly: no deadlock, no drift.
    @Test
    void oppositeSwapsDoNotDeadlock() throws Exception {
        JsonNode poll = createPoll("A", "B");
        long pollId = poll.get("id").asLong();
        List<Long> opts = optionIds(poll);
        long a = opts.get(0);
        long b = opts.get(1);
        castAll(pollId, opts); // voter i -> opts[i % 2]
        int flips = 6;

        List<Resp> results = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            List<Callable<Resp>> tasks = new ArrayList<>();
            for (int i = 0; i < VOTERS; i++) {
                User u = voters.get(i);
                boolean startsOnA = i % 2 == 0;
                tasks.add(() -> {
                    Resp last = null;
                    for (int f = 0; f < flips; f++) {
                        boolean onA = startsOnA == (f % 2 == 0);
                        last = send("PUT", "/api/v1/polls/" + pollId + "/votes", u, Map.of("optionId", onA ? b : a));
                        if (last.status() != 200) {
                            return last;
                        }
                    }
                    return last;
                });
            }
            return runConcurrently(tasks);
        });

        assertNoServerErrors(results);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as("swap: %s", r.body()).isEqualTo(200));
        // an even number of flips puts everyone back where they started
        assertThat(voteCount(a)).isEqualTo(VOTERS / 2);
        assertThat(voteCount(b)).isEqualTo(VOTERS / 2);
        assertCountsConsistent(pollId, VOTERS);
        assertThat(auditCount(pollId, "VOTE_CHANGED")).isEqualTo(VOTERS * flips);
        assertAuditChainsMatchVotes(pollId);
    }

    // ---------------------------------------------------------------- helpers

    private void castAll(long pollId, List<Long> opts) throws Exception {
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < VOTERS; i++) {
            User u = voters.get(i);
            long opt = opts.get(i % opts.size());
            tasks.add(() -> send("POST", "/api/v1/polls/" + pollId + "/votes", u, Map.of("optionId", opt)));
        }
        List<Resp> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as("cast: %s", r.body()).isEqualTo(201));
    }

    /** Starts every task on its own thread, releases them together, and returns results in task order. */
    private List<Resp> runConcurrently(List<Callable<Resp>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 128));
        try {
            CountDownLatch ready = new CountDownLatch(Math.min(tasks.size(), 128));
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Callable<Resp> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("threads ready").isTrue();
            go.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Resp send(String method, String path, User user, Object body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (user != null) {
            req.header("Authorization", "Bearer " + user.token());
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(res.statusCode(), res.body().isEmpty() ? null : json.readTree(res.body()));
    }

    private Resp registerRaw() throws Exception {
        String name = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return send("POST", "/api/v1/auth/register", null, Map.of("username", name, "password", "password123"));
    }

    private User register() throws Exception {
        Resp r = registerRaw();
        assertThat(r.status()).isEqualTo(201);
        return new User(r.body().get("token").asText(), r.body().get("userId").asLong());
    }

    private JsonNode createPoll(String... options) throws Exception {
        Resp r = send("POST", "/api/v1/polls", creator, Map.of("question", "Concurrency?", "options", List.of(options)));
        assertThat(r.status()).as("create: %s", r.body()).isEqualTo(201);
        return r.body();
    }

    private static List<Long> optionIds(JsonNode poll) {
        List<Long> ids = new ArrayList<>();
        poll.get("options").forEach(o -> ids.add(o.get("optionId").asLong()));
        return ids;
    }

    private static Map<String, Object> editBody(String question, long version, Object... idTextPairs) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < idTextPairs.length; i += 2) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", idTextPairs[i]);
            o.put("text", idTextPairs[i + 1]);
            opts.add(o);
        }
        return Map.of("question", question, "version", version, "options", opts);
    }

    private static void assertNoServerErrors(List<Resp> results) {
        assertThat(results).allSatisfy(r ->
                assertThat(r.status()).as("unexpected server error: %s", r.body()).isLessThan(500));
    }

    /** vote_count == COUNT(vote) for every option of the poll, and the counts sum to the number of voters. */
    private void assertCountsConsistent(long pollId, int expectedTotal) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id, o.vote_count, (SELECT COUNT(*) FROM vote v WHERE v.option_id = o.id) AS actual"
                        + " FROM poll_option o WHERE o.poll_id = ?", pollId);
        long sum = 0;
        for (Map<String, Object> row : rows) {
            long counter = ((Number) row.get("VOTE_COUNT")).longValue();
            long actual = ((Number) row.get("ACTUAL")).longValue();
            assertThat(counter).as("option %s vote_count vs COUNT(vote)", row.get("ID")).isEqualTo(actual);
            sum += counter;
        }
        assertThat(sum).as("sum of vote_count").isEqualTo(expectedTotal);
        assertThat(voteRows(pollId)).isEqualTo(expectedTotal);
    }

    /**
     * Replays each voter's audit trail (VOTE_CAST then VOTE_CHANGED "old -> new") in id order: every change
     * must start where the previous one ended, and the last one must match the vote row. Catches lost updates.
     */
    private void assertAuditChainsMatchVotes(long pollId) {
        Map<Long, Long> current = new HashMap<>();
        jdbc.query("SELECT actor_id, action, details FROM audit_event WHERE poll_id = ? ORDER BY id", rs -> {
            long actor = rs.getLong("actor_id");
            String action = rs.getString("action");
            String details = rs.getString("details");
            if (action.equals("VOTE_CAST")) {
                current.put(actor, Long.parseLong(details.replaceAll("\\D", "")));
            } else if (action.equals("VOTE_CHANGED")) {
                Matcher m = CHANGE_DETAILS.matcher(details);
                assertThat(m.find()).as(details).isTrue();
                assertThat(Long.parseLong(m.group(1))).as("chain for user %d", actor).isEqualTo(current.get(actor));
                current.put(actor, Long.parseLong(m.group(2)));
            }
        }, pollId);
        Map<Long, Long> inDb = new HashMap<>();
        jdbc.query("SELECT user_id, option_id FROM vote WHERE poll_id = ?",
                rs -> { inDb.put(rs.getLong("user_id"), rs.getLong("option_id")); }, pollId);
        assertThat(current).isEqualTo(inDb);
    }

    private long currentVersion(long pollId) {
        return jdbc.queryForObject("SELECT version FROM poll WHERE id = ?", Long.class, pollId);
    }

    private long voteRows(long pollId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE poll_id = ?", Long.class, pollId);
    }

    private int voteCount(long optionId) {
        return jdbc.queryForObject("SELECT vote_count FROM poll_option WHERE id = ?", Integer.class, optionId);
    }

    private boolean optionExists(long optionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM poll_option WHERE id = ?", Integer.class, optionId) == 1;
    }

    private long auditCount(long pollId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE poll_id = ? AND action = ?", Long.class,
                pollId, action);
    }
}
