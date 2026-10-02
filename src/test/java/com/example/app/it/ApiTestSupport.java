package com.example.app.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared setup for the MockMvc integration tests: the real app on an in-memory H2 DB (never ./data).
 * All subclasses share one Spring context and therefore one DB, so every test uses unique usernames.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:itdb;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
abstract class ApiTestSupport {

    static final String PASSWORD = "password123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    /** A registered user: raw bearer token, id and (lower-cased) username. */
    record TestUser(String token, long id, String username) {
    }

    static String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    TestUser register(String prefix) throws Exception {
        String name = uniqueName(prefix);
        JsonNode body = expect(post("/api/v1/auth/register"), null,
                Map.of("username", name, "password", PASSWORD), 201);
        return new TestUser(body.get("token").asText(), body.get("userId").asLong(), body.get("username").asText());
    }

    JsonNode createPoll(TestUser user, String question, String... options) throws Exception {
        return expect(post("/api/v1/polls"), user,
                Map.of("question", question, "options", List.of(options)), 201);
    }

    /** Builds an edit body: each entry is {id, text}; id null means "add". */
    static Map<String, Object> editBody(String question, long version, Object... idTextPairs) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < idTextPairs.length; i += 2) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", idTextPairs[i]);
            o.put("text", idTextPairs[i + 1]);
            opts.add(o);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("version", version);
        body.put("options", opts);
        return body;
    }

    static long optionId(JsonNode poll, int index) {
        return poll.get("options").get(index).get("optionId").asLong();
    }

    MvcResult call(MockHttpServletRequestBuilder req, TestUser user, Object body) throws Exception {
        if (user != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn();
    }

    /** Performs the request, asserts the status and returns the parsed JSON body (or null if empty). */
    JsonNode expect(MockHttpServletRequestBuilder req, TestUser user, Object body, int status) throws Exception {
        MvcResult result = call(req, user, body);
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("status of %s %s, body: %s", result.getRequest().getMethod(),
                        result.getRequest().getRequestURI(), content)
                .isEqualTo(status);
        return content.isEmpty() ? null : json.readTree(content);
    }

    /** Asserts the {status, error, message} error shape. */
    static void assertError(JsonNode body, int status) {
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("error").asText()).isNotBlank();
        assertThat(body.get("message").asText()).isNotBlank();
    }

    JsonNode vote(TestUser user, long pollId, long optionId, int status) throws Exception {
        return expect(post("/api/v1/polls/" + pollId + "/votes"), user, Map.of("optionId", optionId), status);
    }

    JsonNode changeVote(TestUser user, long pollId, long optionId, int status) throws Exception {
        return expect(put("/api/v1/polls/" + pollId + "/votes"), user, Map.of("optionId", optionId), status);
    }

    JsonNode getPoll(TestUser user, long pollId) throws Exception {
        return expect(get("/api/v1/polls/" + pollId), user, null, 200);
    }

    int dbVoteCount(long optionId) {
        return jdbc.queryForObject("SELECT vote_count FROM poll_option WHERE id = ?", Integer.class, optionId);
    }

    long dbVoteRows(long optionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE option_id = ?", Long.class, optionId);
    }
}
