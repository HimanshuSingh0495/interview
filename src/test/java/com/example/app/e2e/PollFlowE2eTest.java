package com.example.app.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Scenarios 2-5: create a poll, copy its link, a friend registers and votes, then changes the vote. */
@Tag("e2e")
class PollFlowE2eTest extends E2eSupport {

    private static final Pattern SHARE_URL = Pattern.compile(".*/p/([^/?#]+)$");

    static Locator resultRow(Page page, String optionText) {
        return page.getByTestId("result-row").filter(new Locator.FilterOptions()
                .setHas(page.getByTestId("result-row-text").getByText(optionText, new Locator.GetByTextOptions().setExact(false))));
    }

    static Locator radioFor(Page page, String optionText) {
        // Role-scoped: the chart canvas's aria-label also mentions every option text.
        return page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(optionText));
    }

    @Test
    void createPollWithAddedAndRemovedOptionLandsOnEmptyPoll() {
        Auth owner = api.register(uniqueName("creator"));
        Page page = newPage(owner);

        page.navigate("/");
        page.getByTestId("new-poll-btn").click();
        page.waitForURL(baseUrl() + "/polls/new");

        Locator rows = page.getByTestId("option-row");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0).getByTestId("remove-option-btn")).isDisabled();
        assertThat(rows.nth(1).getByTestId("remove-option-btn")).isDisabled();

        String question = "Where should we eat on Friday? " + uniqueName("q");
        page.getByTestId("question-input").fill(question);
        rows.nth(0).getByTestId("option-input").fill("Tacos");
        rows.nth(1).getByTestId("option-input").fill("Doomed answer");

        page.getByTestId("add-option-btn").click();
        assertThat(rows).hasCount(3);
        rows.nth(2).getByTestId("option-input").fill("Ramen");
        assertThat(rows.nth(1).getByTestId("remove-option-btn")).isEnabled();

        rows.nth(1).getByTestId("remove-option-btn").click();
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0).getByTestId("option-input")).hasValue("Tacos");
        assertThat(rows.nth(1).getByTestId("option-input")).hasValue("Ramen");
        assertThat(rows.nth(0).getByTestId("remove-option-btn")).isDisabled();
        shot(page, "02a-create-poll-form");

        page.getByTestId("save-btn").click();

        page.waitForURL(SHARE_URL);
        assertThat(page.getByTestId("poll-question")).hasText(question);
        assertThat(page.getByTestId("total-votes")).hasText("0 votes");
        assertThat(page.getByText("No votes yet")).isVisible();
        assertThat(page.getByTestId("results-chart")).isHidden();
        assertThat(page.getByTestId("results-chart")).hasAttribute("data-total", "0");
        assertThat(page.getByTestId("result-row")).hasCount(2);
        assertThat(resultRow(page, "Tacos").getByTestId("result-row-count")).hasText("0 votes");
        assertThat(page.getByTestId("result-row-text").getByText("Doomed answer")).hasCount(0);
        assertThat(page.getByTestId("edit-poll-link")).isVisible();
        shot(page, "02b-new-poll-no-votes");
    }

    @Test
    void copyLinkPutsShareUrlOnClipboardAndShowsToast() {
        Auth owner = api.register(uniqueName("copier"));
        PollRef poll = api.createPoll(owner, "Copy me?", "Yes", "No");
        Page page = newPage(owner);

        page.navigate("/p/" + poll.shareId());
        assertThat(page.getByTestId("poll-question")).hasText("Copy me?");
        page.getByTestId("copy-link-btn").click();

        assertThat(page.getByTestId("toast")).isVisible();
        assertThat(page.getByTestId("toast")).hasText("Link copied");
        Object clip = page.evaluate("() => navigator.clipboard.readText()");
        assertThat(clip).isEqualTo(baseUrl() + "/p/" + poll.shareId());
        shot(page, "03-copy-link-toast");

        // My polls has its own copy button per row.
        page.evaluate("() => navigator.clipboard.writeText('')");
        page.navigate("/");
        page.getByTestId("poll-row").first().getByTestId("copy-link-btn").click();
        assertThat(page.getByTestId("toast")).hasText("Link copied");
        assertThat(page.evaluate("() => navigator.clipboard.readText()")).isEqualTo(baseUrl() + "/p/" + poll.shareId());
    }

    @Test
    void friendOpensLinkRegistersVotesAndSeesChart() {
        Auth owner = api.register(uniqueName("ownera"));
        PollRef poll = api.createPoll(owner, "Best pizza topping?", "Mushroom", "Pineapple", "Pepperoni");

        Page b = newPage(null);
        b.navigate("/p/" + poll.shareId());

        // Logged out: results are public, voting asks for a login.
        assertThat(b.getByTestId("poll-question")).hasText("Best pizza topping?");
        assertThat(b.getByTestId("vote-btn")).isHidden();
        assertThat(b.getByTestId("edit-poll-link")).isHidden();
        Locator loginToVote = b.getByTestId("login-to-vote-link");
        assertThat(loginToVote).isVisible();
        assertThat(loginToVote).hasAttribute("href", "/login?next=/p/" + poll.shareId());
        shot(b, "04a-friend-logged-out-login-to-vote");

        loginToVote.click();
        b.waitForURL(baseUrl() + "/login?next=/p/" + poll.shareId());
        b.getByTestId("switch-auth-link").click();
        b.waitForURL(baseUrl() + "/register?next=/p/" + poll.shareId());

        String friend = uniqueName("friendb");
        fillAuthForm(b, friend, PASSWORD);

        b.waitForURL(baseUrl() + "/p/" + poll.shareId());
        assertLoggedInNav(b, friend);
        assertThat(b.getByTestId("login-to-vote-link")).isHidden();
        Locator voteBtn = b.getByTestId("vote-btn");
        assertThat(voteBtn).isVisible();
        assertThat(voteBtn).hasText("Vote");
        assertThat(voteBtn).isDisabled();
        assertThat(b.getByTestId("edit-poll-link")).isHidden();

        radioFor(b, "Pineapple").check();
        assertThat(voteBtn).isEnabled();
        voteBtn.click();

        assertThat(b.getByTestId("status-msg")).hasText("Vote saved");
        assertThat(voteBtn).hasText("Change vote");
        Locator chart = b.getByTestId("results-chart");
        assertThat(chart).isVisible();
        assertThat(chart).hasAttribute("data-total", "1");
        assertThat(b.getByText("No votes yet")).isHidden();
        assertThat(b.getByTestId("total-votes")).hasText("1 vote");
        assertThat(resultRow(b, "Pineapple").getByTestId("result-row-count")).hasText("1 vote");
        assertThat(resultRow(b, "Pineapple").getByTestId("result-row-pct")).hasText("100%");
        assertThat(resultRow(b, "Mushroom").getByTestId("result-row-count")).hasText("0 votes");
        assertThat(radioFor(b, "Pineapple")).isChecked();

        waitForPie(b);
        shot(b, "04b-friend-votes-pie-chart");
    }

    @Test
    void friendChangesVoteAndChoiceSurvivesReload() {
        Auth owner = api.register(uniqueName("ownerc"));
        PollRef poll = api.createPoll(owner, "Weekend plan?", "Hiking", "Cinema", "Sleeping in");
        Auth friend = api.register(uniqueName("friendc"));
        Auth other = api.register(uniqueName("otherc"));
        api.vote(other, poll, poll.optionId(1)); // someone else already voted Cinema

        Page b = newPage(friend);
        b.navigate("/p/" + poll.shareId());
        Locator voteBtn = b.getByTestId("vote-btn");
        assertThat(voteBtn).hasText("Vote");

        radioFor(b, "Hiking").check();
        voteBtn.click();
        assertThat(b.getByTestId("status-msg")).hasText("Vote saved");
        assertThat(voteBtn).hasText("Change vote");
        assertThat(resultRow(b, "Hiking").getByTestId("result-row-count")).hasText("1 vote");
        assertThat(resultRow(b, "Cinema").getByTestId("result-row-count")).hasText("1 vote");
        assertThat(b.getByTestId("results-chart")).hasAttribute("data-total", "2");

        radioFor(b, "Cinema").check();
        assertThat(voteBtn).isEnabled();
        voteBtn.click();

        assertThat(b.getByTestId("status-msg")).hasText("Vote changed");
        assertThat(voteBtn).hasText("Change vote");
        assertThat(resultRow(b, "Hiking").getByTestId("result-row-count")).hasText("0 votes");
        assertThat(resultRow(b, "Cinema").getByTestId("result-row-count")).hasText("2 votes");
        assertThat(resultRow(b, "Cinema").getByTestId("result-row-pct")).hasText("100%");
        assertThat(b.getByTestId("results-chart")).hasAttribute("data-total", "2");
        assertThat(b.getByTestId("total-votes")).hasText("2 votes");
        waitForPie(b);
        shot(b, "05a-change-vote");

        b.reload();
        assertThat(b.getByTestId("poll-question")).hasText("Weekend plan?");
        assertThat(radioFor(b, "Cinema")).isChecked();
        assertThat(radioFor(b, "Hiking")).not().isChecked();
        assertThat(b.getByTestId("vote-btn")).hasText("Change vote");
        assertThat(resultRow(b, "Cinema").getByTestId("result-row-count")).hasText("2 votes");
        assertThat(b.getByTestId("option-radio")).hasCount(3);
        assertThat(b.getByTestId("option-radio").and(b.locator(":checked"))).hasCount(1);
        assertThat(b.url()).endsWith("/p/" + poll.shareId());
        waitForPie(b);
        shot(b, "05b-reload-keeps-vote");
    }
}
