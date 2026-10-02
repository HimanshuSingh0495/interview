package com.example.app.e2e;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Scenarios 1, 10 (login + logged-out redirect) and 11: registration, login errors, logout. */
@Tag("e2e")
class AuthE2eTest extends E2eSupport {

    @Test
    void registerLandsOnEmptyMyPollsWithUsernameInNav() {
        Page page = newPage(null);
        String username = uniqueName("reg");

        page.navigate("/register");
        assertThat(page.getByTestId("nav-login")).isVisible();
        assertThat(page.getByTestId("nav-username")).isHidden();

        fillAuthForm(page, username, PASSWORD);

        page.waitForURL(baseUrl() + "/");
        assertThat(page.getByTestId("empty-state")).isVisible();
        assertThat(page.getByTestId("poll-row")).hasCount(0);
        assertLoggedInNav(page, username);
        shot(page, "01-register");
    }

    @Test
    void loginWithWrongPasswordShowsError() {
        Auth user = api.register(uniqueName("wrongpw"));
        Page page = newPage(null);

        page.navigate("/login");
        assertThat(page.getByTestId("error-msg")).isHidden();
        fillAuthForm(page, user.username(), "not-the-password");

        assertThat(page.getByTestId("error-msg")).isVisible();
        assertThat(page.getByTestId("error-msg")).not().hasText("");
        assertThat(page).hasURL(Pattern.compile("/login"));
        assertThat(page.getByTestId("nav-username")).isHidden();
        assertThat(page.evaluate("localStorage.getItem('pollapp.token')")).isNull();
        shot(page, "10a-login-wrong-password");

        // The right password works.
        page.getByTestId("password-input").fill(PASSWORD);
        page.getByTestId("submit-btn").click();
        page.waitForURL(baseUrl() + "/");
        assertLoggedInNav(page, user.username());
    }

    @Test
    void loggedOutHomeRedirectsToLoginWithNext() {
        Page page = newPage(null);
        page.navigate("/");
        page.waitForURL(baseUrl() + "/login?next=/");
        assertThat(page.getByTestId("username-input")).isVisible();
        shot(page, "10c-logged-out-redirect-to-login");
    }

    @Test
    void logoutClearsTokenAndNav() {
        Auth user = api.register(uniqueName("logout"));
        Page page = newPage(user);

        page.navigate("/");
        assertLoggedInNav(page, user.username());
        assertThat(page.getByTestId("empty-state")).isVisible();

        page.getByTestId("logout-btn").click();

        page.waitForURL(Pattern.compile(".*/login.*"));
        assertThat(page.getByTestId("nav-login")).isVisible();
        assertThat(page.getByTestId("nav-username")).isHidden();
        assertThat(page.getByTestId("nav-my-polls")).isHidden();
        assertThat(page.getByTestId("logout-btn")).isHidden();
        assertThat(page.evaluate("localStorage.getItem('pollapp.token')")).isNull();
        assertThat(page.evaluate("localStorage.getItem('pollapp.user')")).isNull();
        shot(page, "11-logout");

        // The session really is gone: My polls sends us back to login.
        page.navigate("/");
        page.waitForURL(baseUrl() + "/login?next=/");
    }
}
