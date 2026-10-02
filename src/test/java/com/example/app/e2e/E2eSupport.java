package com.example.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Shared setup for the browser tests: boots the app on a random port against an in-memory H2 DB,
 * one Playwright + headless Chromium per class, fresh {@link BrowserContext}s per test (closed in @AfterEach).
 *
 * <p>Seeding goes through the real JSON API ({@link Api}) so each test only drives the UI for the part it checks.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1")
abstract class E2eSupport {

    static final String PASSWORD = "password123";
    private static final AtomicInteger SEQ = new AtomicInteger();
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    Playwright playwright;
    Browser browser;
    Api api;
    private final List<BrowserContext> contexts = new ArrayList<>();

    @BeforeAll
    void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        api = new Api(playwright.request().newContext(new APIRequest.NewContextOptions().setBaseURL(baseUrl())));
    }

    @AfterAll
    void closeBrowser() {
        if (api != null) api.http.dispose();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void resetContexts() {
        contexts.clear();
    }

    @AfterEach
    void closeContexts() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Unique, valid username (lower case, [a-z0-9_], at most 30 chars). */
    static String uniqueName(String prefix) {
        return (prefix + "_" + Long.toString(System.currentTimeMillis(), 36) + SEQ.incrementAndGet()).toLowerCase();
    }

    /** A fresh, logged-out browser context (a separate "person"). */
    BrowserContext newContext() {
        return newContext(null);
    }

    /** A browser context already logged in as {@code user} (token pre-loaded into localStorage). */
    BrowserContext newContext(Auth user) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setBaseURL(baseUrl())
                .setViewportSize(1280, 800)
                // The poll page turns Chart.js animation off under reduced motion, so the pie is fully drawn
                // as soon as data-total updates (stable screenshots, no waiting on animation frames).
                .setReducedMotion(ReducedMotion.REDUCE)
                .setPermissions(List.of("clipboard-read", "clipboard-write"));
        if (user != null) {
            options.setStorageState(storageState(user));
        }
        BrowserContext context = browser.newContext(options);
        context.setDefaultTimeout(10_000);
        contexts.add(context);
        return context;
    }

    Page newPage(Auth user) {
        Page page = newContext(user).newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) System.out.println("[browser console] " + msg.text());
        });
        page.onPageError(err -> System.out.println("[browser pageerror] " + err));
        return page;
    }

    private String storageState(Auth user) {
        try {
            String userJson = JSON.writeValueAsString(Map.of("userId", user.userId(), "username", user.username()));
            Map<String, Object> state = Map.of(
                    "cookies", List.of(),
                    "origins", List.of(Map.of(
                            "origin", baseUrl(),
                            "localStorage", List.of(
                                    Map.of("name", "pollapp.token", "value", user.token()),
                                    Map.of("name", "pollapp.user", "value", userJson)))));
            return JSON.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registers through the UI on the current /register page and waits until it navigates away. */
    static void fillAuthForm(Page page, String username, String password) {
        page.getByTestId("username-input").fill(username);
        page.getByTestId("password-input").fill(password);
        page.getByTestId("submit-btn").click();
    }

    /** Asserts the nav shows this user as logged in. */
    static void assertLoggedInNav(Page page, String username) {
        assertThat(page.getByTestId("nav-username")).hasText(username);
        assertThat(page.getByTestId("nav-my-polls")).isVisible();
        assertThat(page.getByTestId("logout-btn")).isVisible();
        assertThat(page.getByTestId("nav-login")).isHidden();
    }

    static final Path SCREENSHOTS = Paths.get("docs", "screenshots");

    /** Full-page screenshot for the README: docs/screenshots/{name}.png (directory created on demand). */
    static void shot(Page page, String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOTS.resolve(name + ".png")).setFullPage(true));
    }

    /** Waits until the pie canvas is visible and reports at least one vote; with reduced motion it is then drawn. */
    static void waitForPie(Page page) {
        assertThat(page.getByTestId("results-chart")).isVisible();
        page.waitForFunction("() => Number(document.querySelector('[data-testid=results-chart]').dataset.total) > 0");
        page.waitForFunction("() => { const c = window.Chart && Chart.getChart(document.querySelector('[data-testid=results-chart]'));"
                + " return !!c && c.chartArea && c.chartArea.width > 0; }");
    }

    // ---- API seeding -------------------------------------------------------

    record Auth(String token, long userId, String username) {
    }

    record PollRef(long id, String shareId, JsonNode body) {
        long optionId(int index) {
            return body.get("options").get(index).get("optionId").asLong();
        }
    }

    static final class Api {
        final APIRequestContext http;

        Api(APIRequestContext http) {
            this.http = http;
        }

        Auth register(String username) {
            JsonNode n = call("POST", "/api/v1/auth/register", null, Map.of("username", username, "password", PASSWORD), 201);
            return new Auth(n.get("token").asText(), n.get("userId").asLong(), n.get("username").asText());
        }

        PollRef createPoll(Auth as, String question, String... options) {
            JsonNode n = call("POST", "/api/v1/polls", as, Map.of("question", question, "options", List.of(options)), 201);
            return new PollRef(n.get("id").asLong(), n.get("shareId").asText(), n);
        }

        void vote(Auth as, PollRef poll, long optionId) {
            call("POST", "/api/v1/polls/" + poll.id() + "/votes", as, Map.of("optionId", optionId), 201);
        }

        private JsonNode call(String method, String path, Auth as, Object body, int expectedStatus) {
            RequestOptions options = RequestOptions.create().setMethod(method).setData(body);
            if (as != null) options.setHeader("Authorization", "Bearer " + as.token());
            APIResponse res = http.fetch(path, options);
            if (res.status() != expectedStatus) {
                throw new AssertionError(method + " " + path + " -> " + res.status() + ": " + res.text());
            }
            try {
                return JSON.readTree(res.text());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
