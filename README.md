# Poll app

[![CI](https://github.com/HimanshuSingh0495/interview/actions/workflows/ci.yml/badge.svg)](https://github.com/HimanshuSingh0495/interview/actions/workflows/ci.yml)

Create a poll, share it with friends, vote and change your vote, and see the results as a pie chart.

Stack: Java 17 · Spring Boot 3.5 · Spring Security (bearer token + BCrypt) · Spring Data JPA · Flyway · H2 · Thymeleaf shells with vanilla JS · Chart.js · JUnit 5, MockMvc and Playwright.

## Live demo: https://interview-poll-app.onrender.com

| Account | Username | Password |
|---|---|---|
| Poll owner (has a sample poll with votes) | `demo` | `demo12345` |
| Friend / voter | `friend` | `friend12345` |

Log in as `demo` and open the sample poll from **My polls**, or register a new account.

> Free hosting: the first load after it has been idle takes 30-60 s while the server wakes up. The free host wipes the database when it sleeps or redeploys. The demo accounts and sample poll are recreated automatically on every start (`DemoDataSeeder`), but polls you create yourself are not kept.

## Run it

```bash
mvn spring-boot:run          # http://localhost:8081   (PORT=9000 mvn spring-boot:run for another port)
mvn test                     # unit + integration tests (in-memory DB)
mvn test -Pe2e               # Playwright browser tests
```

Try it:
1. Register, create a poll, then click **Copy link**.
2. Open the link in a private window and register a second user.
3. As that user, vote, see the pie chart update, and then **Change vote**.

## Features

| Requirement | Where |
|---|---|
| Create a poll (question + options) | `POST /api/v1/polls`, page `/polls/new` |
| Edit a poll (creator only) | `PUT /api/v1/polls/{id}`, page `/polls/{id}/edit` |
| Send to friends | Share link `/p/{shareId}` with Copy link and Share buttons |
| Vote, and change the vote later | `POST` / `PUT /api/v1/polls/{id}/votes` (one vote per user per poll) |
| Account with username + password | `POST /api/v1/auth/register`, `/login` (BCrypt; only a SHA-256 of the token is stored) |
| Pie chart of votes | `/p/{shareId}` |
| My previously created polls | `GET /api/v1/users/me/polls`, page `/` |

## Tests

| Suite | Command | Tests | What it proves |
|---|---|---|---|
| Unit (Mockito) | `mvn test` | 73 | Service rules: validation, ownership 403, edit rules, vote/change logic, token hashing |
| Integration (MockMvc + H2) | `mvn test` | 31 | Every endpoint and status code, auth, optimistic-lock 409, audit trail order |
| Concurrency (real HTTP) | `mvn test` | 5 of the 31 | 50 users double-posting; 200 parallel vote changes; racing edits; edit-vs-vote races; A↔B swaps without deadlock. `vote_count` always equals `COUNT(vote)` |
| Browser (Playwright) | `mvn test -Pe2e` | 13 | The 11 user scenarios below, end to end in Chromium |

Latest local run: `mvn test` gives 105 passed, 0 failed. `mvn test -Pe2e` gives 13 passed, 0 failed.

## Screenshots (captured by the Playwright tests)

Each image is taken by `mvn test -Pe2e` right after that scenario's assertions pass (`src/test/java/com/example/app/e2e/`).

### 1. Register
New user lands on an empty *My polls*; the username shows in the nav. (test: `AuthE2eTest`)

![1. Register](docs/screenshots/01-register.png)

### 2a. Create poll: form
Dynamic answers (2–10): one added, one removed. (test: `PollFlowE2eTest`)

![2a. Create poll: form](docs/screenshots/02a-create-poll-form.png)

### 2b. Create poll: result
Redirects to the share page with 0 votes and a *No votes yet* state. (test: `PollFlowE2eTest`)

![2b. Create poll: result](docs/screenshots/02b-new-poll-no-votes.png)

### 3. Send to friends
*Copy link* puts `<origin>/p/{shareId}` on the clipboard and shows a toast. (test: `PollFlowE2eTest`)

![3. Send to friends](docs/screenshots/03-copy-link-toast.png)

### 4a. Friend opens the link
Logged out, the friend sees the results and *Log in to vote*. (test: `PollFlowE2eTest`)

![4a. Friend opens the link](docs/screenshots/04a-friend-logged-out-login-to-vote.png)

### 4b. Friend votes: pie chart
*Vote saved*, the button becomes *Change vote*, and the pie shows 1 vote (100%). (test: `PollFlowE2eTest`)

![4b. Friend votes: pie chart](docs/screenshots/04b-friend-votes-pie-chart.png)

### 5a. Change vote
*Vote changed*; counts move to the new option. (test: `PollFlowE2eTest`)

![5a. Change vote](docs/screenshots/05a-change-vote.png)

### 5b. Vote persists
After a reload, the friend's vote is still pre-selected. (test: `PollFlowE2eTest`)

![5b. Vote persists](docs/screenshots/05b-reload-keeps-vote.png)

### 6a. Owner edits
Renames the voted answer and adds one. Remove is locked on answers that have votes. (test: `OwnerE2eTest`)

![6a. Owner edits](docs/screenshots/06a-owner-edit-form.png)

### 6b. Edit saved
The renamed answer keeps its votes. (test: `OwnerE2eTest`)

![6b. Edit saved](docs/screenshots/06b-owner-edit-saved.png)

### 7. Concurrent edit (optimistic lock)
A second tab saving a stale version gets 409 *Poll was changed by someone else* and a *Reload* button. (test: `OwnerE2eTest`)

![7. Concurrent edit (optimistic lock)](docs/screenshots/07-stale-edit-conflict.png)

### 8. My polls
Only my polls, newest first, with vote counts and Open/Edit/Copy link. (test: `OwnerE2eTest`)

![8. My polls](docs/screenshots/08-my-polls.png)

### 9a. Access control: view
A non-owner sees no Edit link. (test: `OwnerE2eTest`)

![9a. Access control: view](docs/screenshots/09a-non-owner-no-edit-link.png)

### 9b. Access control: edit URL
A non-owner opening `/polls/{id}/edit` gets a 403 message and no form. (test: `OwnerE2eTest`)

![9b. Access control: edit URL](docs/screenshots/09b-non-owner-edit-page-error.png)

### 10a. Validation: wrong password
Login error message. (test: `AuthE2eTest`)

![10a. Validation: wrong password](docs/screenshots/10a-login-wrong-password.png)

### 10b. Validation: duplicate answers
*Cats* / *cats* rejected; nothing is created. (test: `OwnerE2eTest`)

![10b. Validation: duplicate answers](docs/screenshots/10b-duplicate-options.png)

### 10c. Auth guard
A logged-out visit to `/` redirects to `/login?next=/`. (test: `AuthE2eTest`)

![10c. Auth guard](docs/screenshots/10c-logged-out-redirect-to-login.png)

### 11. Logout
Token cleared and the nav is back to logged out. (test: `AuthE2eTest`)

![11. Logout](docs/screenshots/11-logout.png)

## Concurrency and integrity

- **No lost votes.** Vote counts change only through SQL arithmetic (`vote_count = vote_count + 1`), never by reading the value, changing it in Java and writing it back. The entity mapping also marks the column as non-updatable.
- **One vote per user per poll.** The database enforces `UNIQUE(user_id, poll_id)`, so a double submit returns 409.
- **Concurrent vote changes are serialised.** Changing a vote locks the user's vote row (`SELECT … FOR UPDATE`). Counters are updated in option-id order, which prevents deadlocks.
- **Concurrent poll edits are caught.** Optimistic locking (`@Version`) rejects a stale edit with 409.
- **Options with votes can't be deleted.** Removing such an option returns 409, and a foreign key from `vote` also blocks the delete at the database level.
- **Audit trail.** Every action (register, create, edit, vote, change vote) is appended to `audit_event` in the same transaction.

**Manual test plan:** [`docs/MANUAL_TEST_PLAN.md`](docs/MANUAL_TEST_PLAN.md) has 62 cases with curl and SQL checks. Concurrency check: `./scripts/vote-storm.sh http://localhost:8081 30` fires parallel votes and prints PASS/FAIL.

Design: [`docs/PLAN.md`](docs/PLAN.md). Implementation contract: [`docs/CONTRACT.md`](docs/CONTRACT.md).

## Database

- **Local file:** `./data/appdb.mv.db`. Delete `data/` to reset.
- **Browser console:** http://localhost:8081/h2-console. Use JDBC URL `jdbc:h2:file:./data/appdb;AUTO_SERVER=TRUE`, user `sa`, and an empty password.
- **Terminal:** `./scripts/db.sh "SELECT * FROM audit_event"`
