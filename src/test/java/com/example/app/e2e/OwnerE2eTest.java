package com.example.app.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.example.app.e2e.PollFlowE2eTest.resultRow;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Scenarios 6-9 and the poll-form part of 10: editing, stale edits, My polls, access control, validation. */
@Tag("e2e")
class OwnerE2eTest extends E2eSupport {

    private static void waitForEditForm(Page page, String question) {
        assertThat(page.getByTestId("question-input")).hasValue(question);
    }

    @Test
    void ownerRenamesAndAddsOptionsAndVotesAreKept() {
        Auth owner = api.register(uniqueName("editor"));
        PollRef poll = api.createPoll(owner, "Team lunch?", "Sushi", "Burgers");
        Auth friend = api.register(uniqueName("voter"));
        api.vote(friend, poll, poll.optionId(0)); // Sushi has 1 vote

        Page a = newPage(owner);
        a.navigate("/p/" + poll.shareId());
        a.getByTestId("edit-poll-link").click();
        a.waitForURL(baseUrl() + "/polls/" + poll.id() + "/edit");
        waitForEditForm(a, "Team lunch?");

        Locator rows = a.getByTestId("option-row");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0).getByTestId("option-input")).hasValue("Sushi");
        // Voted option can't be removed; at 2 rows nothing can be removed anyway.
        assertThat(rows.nth(0).getByTestId("remove-option-btn")).isDisabled();

        rows.nth(0).getByTestId("option-input").fill("Sushi (downtown)");
        a.getByTestId("add-option-btn").click();
        assertThat(rows).hasCount(3);
        rows.nth(2).getByTestId("option-input").fill("Salad bar");

        // With 3 rows, the unvoted option becomes removable but the voted one stays locked.
        assertThat(rows.nth(0).getByTestId("remove-option-btn")).isDisabled();
        assertThat(rows.nth(1).getByTestId("remove-option-btn")).isEnabled();
        assertThat(rows.nth(2).getByTestId("remove-option-btn")).isEnabled();
        shot(a, "06a-owner-edit-form");

        a.getByTestId("save-btn").click();

        a.waitForURL(baseUrl() + "/p/" + poll.shareId());
        assertThat(a.getByTestId("result-row")).hasCount(3);
        assertThat(resultRow(a, "Sushi (downtown)").getByTestId("result-row-count")).hasText("1 vote");
        assertThat(resultRow(a, "Burgers").getByTestId("result-row-count")).hasText("0 votes");
        assertThat(resultRow(a, "Salad bar").getByTestId("result-row-count")).hasText("0 votes");
        assertThat(a.getByTestId("results-chart")).hasAttribute("data-total", "1");
        assertThat(a.getByTestId("option-radio")).hasCount(3);
        waitForPie(a);
        shot(a, "06b-owner-edit-saved");

        // Back on the edit page the renamed option still carries its vote and stays locked.
        a.getByTestId("edit-poll-link").click();
        waitForEditForm(a, "Team lunch?");
        assertThat(rows).hasCount(3);
        assertThat(rows.nth(0).getByTestId("option-input")).hasValue("Sushi (downtown)");
        assertThat(rows.nth(0)).hasAttribute("data-votes", "1");
        assertThat(rows.nth(0).getByTestId("remove-option-btn")).isDisabled();
        assertThat(rows.nth(2).getByTestId("remove-option-btn")).isEnabled();
    }

    @Test
    void staleEditInSecondTabShowsConflictAndReload() {
        Auth owner = api.register(uniqueName("stale"));
        PollRef poll = api.createPoll(owner, "Original question", "Red", "Blue");

        BrowserContext ctx = newContext(owner);
        Page tab1 = ctx.newPage();
        Page tab2 = ctx.newPage();
        tab1.navigate("/polls/" + poll.id() + "/edit");
        tab2.navigate("/polls/" + poll.id() + "/edit");
        waitForEditForm(tab1, "Original question");
        waitForEditForm(tab2, "Original question");

        tab1.getByTestId("question-input").fill("Saved first");
        tab1.getByTestId("save-btn").click();
        tab1.waitForURL(baseUrl() + "/p/" + poll.shareId());
        assertThat(tab1.getByTestId("poll-question")).hasText("Saved first");

        tab2.getByTestId("question-input").fill("Saved second (stale)");
        tab2.getByTestId("save-btn").click();

        Locator error = tab2.getByTestId("error-msg");
        assertThat(error).isVisible();
        assertThat(error).containsText("changed by someone else");
        Locator reload = tab2.getByTestId("reload-btn");
        assertThat(reload).isVisible();
        assertThat(tab2).hasURL(baseUrl() + "/polls/" + poll.id() + "/edit");
        shot(tab2, "07-stale-edit-conflict");

        reload.click();
        waitForEditForm(tab2, "Saved first");
        assertThat(tab2.getByTestId("error-msg")).isHidden();
    }

    @Test
    void myPollsListsOwnPollsNewestFirstWithVoteCounts() {
        Auth a = api.register(uniqueName("lister"));
        Auth b = api.register(uniqueName("other"));
        PollRef older = api.createPoll(a, "Older poll " + a.username(), "One", "Two");
        PollRef newer = api.createPoll(a, "Newer poll " + a.username(), "Three", "Four");
        PollRef bPoll = api.createPoll(b, "B's poll " + b.username(), "Five", "Six");
        api.vote(b, older, older.optionId(0));
        api.vote(a, older, older.optionId(1));
        api.vote(b, newer, newer.optionId(0));

        Page page = newPage(a);
        page.navigate("/");

        Locator rows = page.getByTestId("poll-row");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0).getByTestId("poll-row-question")).hasText(newer.body().get("question").asText());
        assertThat(rows.nth(0).getByTestId("poll-row-votes")).hasText("1 vote");
        assertThat(rows.nth(1).getByTestId("poll-row-question")).hasText(older.body().get("question").asText());
        assertThat(rows.nth(1).getByTestId("poll-row-votes")).hasText("2 votes");
        assertThat(page.getByText(bPoll.body().get("question").asText())).hasCount(0);
        assertThat(page.getByTestId("empty-state")).isHidden();
        shot(page, "08-my-polls");

        // Open goes to the share page.
        rows.nth(1).getByTestId("open-link").click();
        page.waitForURL(baseUrl() + "/p/" + older.shareId());
        assertThat(page.getByTestId("poll-question")).hasText(older.body().get("question").asText());

        // Edit goes to a pre-filled form.
        page.navigate("/");
        rows.nth(0).getByTestId("edit-link").click();
        page.waitForURL(baseUrl() + "/polls/" + newer.id() + "/edit");
        waitForEditForm(page, newer.body().get("question").asText());
        assertThat(page.getByTestId("option-input").nth(0)).hasValue("Three");

        // The nav link leads back.
        page.getByTestId("nav-my-polls").click();
        page.waitForURL(baseUrl() + "/");
        assertThat(rows).hasCount(2);
    }

    @Test
    void nonOwnerHasNoEditLinkAndCannotUseEditPage() {
        Auth owner = api.register(uniqueName("owner"));
        PollRef poll = api.createPoll(owner, "Owner only edits", "Left", "Right");
        Auth b = api.register(uniqueName("intruder"));

        Page page = newPage(b);
        page.navigate("/p/" + poll.shareId());
        assertThat(page.getByTestId("poll-question")).hasText("Owner only edits");
        assertThat(page.getByTestId("vote-btn")).isVisible();
        assertThat(page.getByTestId("edit-poll-link")).isHidden();
        shot(page, "09a-non-owner-no-edit-link");

        page.navigate("/polls/" + poll.id() + "/edit");
        Locator error = page.getByTestId("error-msg");
        assertThat(error).isVisible();
        assertThat(error).containsText("Only the person who created this poll can edit it");
        assertThat(page.getByTestId("save-btn")).isHidden();
        assertThat(page.getByTestId("question-input")).isHidden();
        assertThat(page.getByTestId("option-row")).hasCount(0);
        shot(page, "09b-non-owner-edit-page-error");

        // Logged out visitors don't get an edit link either.
        Page anon = newPage(null);
        anon.navigate("/p/" + poll.shareId());
        assertThat(anon.getByTestId("poll-question")).hasText("Owner only edits");
        assertThat(anon.getByTestId("edit-poll-link")).isHidden();
    }

    @Test
    void duplicateOptionsShowErrorAndStayOnForm() {
        Auth owner = api.register(uniqueName("dupes"));
        Page page = newPage(owner);
        page.navigate("/polls/new");

        assertThat(page.getByTestId("error-msg")).isHidden();
        page.getByTestId("question-input").fill("Cats or dogs?");
        Locator inputs = page.getByTestId("option-input");
        inputs.nth(0).fill("Cats");
        inputs.nth(1).fill("cats");
        page.getByTestId("save-btn").click();

        Locator error = page.getByTestId("error-msg");
        assertThat(error).isVisible();
        assertThat(error).containsText(Pattern.compile("duplicate", Pattern.CASE_INSENSITIVE));
        assertThat(page).hasURL(baseUrl() + "/polls/new");
        assertThat(inputs.nth(1)).hasAttribute("aria-invalid", "true");
        shot(page, "10b-duplicate-options");

        // Nothing was created.
        page.navigate("/");
        assertThat(page.getByTestId("empty-state")).isVisible();
    }
}
