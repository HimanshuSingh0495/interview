# Poll app: plan

Status: **draft for review**. Nothing is implemented yet.

## 1. What we're building (from the problem statement)

| # | Requirement | How it's met |
|---|---|---|
| R1 | Create a poll with a question and a list of answer options | `POST /api/v1/polls` + page `/polls/new` |
| R2 | Edit the poll after creating it | `PUT /api/v1/polls/{id}` (creator only) + page `/polls/{id}/edit` |
| R3 | Send the poll to friends | Each poll gets a random `share_id`. The page `/p/{shareId}` has **Copy link** and **Share** (Web Share API, falls back to `mailto:`). No email server. |
| R4 | Vote, and change the vote later | `POST` / `PUT /api/v1/polls/{id}/votes`. One vote per user per poll. |
| R5 | Register and log in with username + password | `POST /api/v1/auth/register`, `/login`. Passwords hashed with BCrypt. |
| R6 | See a graph of the votes (pie chart) | Chart.js pie on `/p/{shareId}`, redrawn after each vote |
| R7 | See all my previously created polls | `GET /api/v1/users/me/polls` + home page `/` |
| NF1 | No race conditions or lost updates on poll edits | Optimistic locking: `poll.version`. A stale edit returns **409**. |
| NF2 | No vote is ever lost under concurrency | The counter changes in SQL (`vote_count = vote_count + 1`), the `UNIQUE(user_id, poll_id)` constraint is enforced by the DB, and the user's vote row is locked while it's changed (details in §4) |
| NF3 | Every action is auditable | The `vote` table holds each user's current vote. An append-only `audit_event` table records every action, including vote changes (old option → new option). |

## 2. Decisions I made: please confirm or change

1. **Route clash.** The spec has both `GET /api/v1/polls/{id}` and `GET /api/v1/polls/{sharableId}`, which are the same URL shape. **Decision:** the share route becomes `GET /api/v1/polls/share/{shareId}`.
2. **Auth token.** **Decision:** an opaque random token (`Authorization: Bearer <token>`). Only its SHA-256 hash is stored, in a new `auth_token` table with a 7-day expiry. It's simpler than JWT, needs no extra library, and can be revoked. Spring Security provides the filter chain and BCrypt.
3. **Editing options when votes exist.** Deleting an option that has votes would lose those votes (NF2). **Decision:** the edit DTO sends options as `[{id?, text}]` instead of `List<String>`:
   - an option **with** an `id` keeps its votes and can be renamed
   - an option **without** an `id` is added
   - an existing option left out of the list is deleted only if it has 0 votes; otherwise the request returns **409**
4. **Audit.** The spec says "use the votes table". With `UNIQUE(user, poll)`, that table only holds the latest vote, so changes would overwrite history. **Decision:** keep `vote` as the current state and add an append-only `audit_event` table for register, create poll, edit poll, cast vote and change vote.
5. **Who sees what.**
   - Results through the share link are **public** (no login needed to view).
   - **Voting needs a login.** A friend who opens the link is asked to log in or register, then lands back on the poll.
   - `GET /polls/{id}` and `PUT /polls/{id}` are **creator only** (403 otherwise).
6. **Status.** The `status` column exists (`OPEN`/`CLOSED`, default `OPEN`) and voting on a `CLOSED` poll returns 409. There is **no endpoint to close a poll** because it isn't in the requirements.
7. **Table names.** `users` (because `USER` is reserved in H2) and `poll_option` (instead of `option`).
8. **UI stack.** Server-served HTML pages (Thymeleaf shells) with vanilla JS calling the JSON API. The token is kept in `localStorage`. There's no React or build step, and Chart.js loads from a CDN.
9. **Playwright.** Playwright for Java runs inside `mvn` against the app started on a random port, so there's one toolchain. These tests are tagged `e2e` and **excluded from plain `mvn test`** because the first run downloads browsers. Run them with `mvn test -Pe2e`.
10. **Git.** No commits. Everything is left uncommitted for you to review in IntelliJ.

## 3. Database (Flyway `V1__poll_schema.sql`)

```
users        id PK, username VARCHAR(30) NOT NULL UNIQUE (stored lower-case),
             password_hash VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL

poll         id PK, share_id VARCHAR(12) NOT NULL UNIQUE, creator_id FK→users NOT NULL,
             question VARCHAR(300) NOT NULL, status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
             version BIGINT NOT NULL DEFAULT 0, created_at, updated_at       idx(creator_id)

poll_option  id PK, poll_id FK→poll NOT NULL, option_text VARCHAR(100) NOT NULL,
             position INT NOT NULL, vote_count INT NOT NULL DEFAULT 0 CHECK (vote_count >= 0)

vote         id PK, poll_id FK→poll, user_id FK→users, option_id FK→poll_option (all NOT NULL),
             created_at, updated_at, UNIQUE(user_id, poll_id)                 idx(poll_id)

auth_token   id PK, user_id FK→users, token_hash CHAR(64) UNIQUE, created_at, expires_at

audit_event  id PK, actor_id FK→users NULL, action VARCHAR(30) NOT NULL, poll_id NULL,
             details VARCHAR(500), created_at        actions: USER_REGISTERED, POLL_CREATED,
                                                     POLL_UPDATED, VOTE_CAST, VOTE_CHANGED
```

The FK from `vote.option_id` means the database itself refuses to delete an option that still has votes. That's a second safety net behind the service check.

## 4. Concurrency design (NF1, NF2)

Every write runs in a single `@Transactional` service method, so the audit row commits or rolls back together with the change.

- **Cast vote**
  1. `INSERT vote`. A double submit, or two tabs voting at once, hits `UNIQUE(user_id, poll_id)` and returns **409** ("already voted, use PUT").
  2. `UPDATE poll_option SET vote_count = vote_count + 1 WHERE id=? AND poll_id=?`. If 0 rows are updated, the option is gone or belongs to another poll, so the request returns 404/409 and rolls back.
- **Change vote**
  1. `SELECT … FOR UPDATE` on the user's vote row (JPA `PESSIMISTIC_WRITE`). Two concurrent changes by the same user run one after the other.
  2. Decrement the old option and increment the new one, both as SQL arithmetic.
  3. Update `vote.option_id`, then audit `VOTE_CHANGED old→new`.
  4. Choosing the same option again does nothing.
- **Why no votes are lost.** Counters are never read into Java, incremented and written back; the database does the arithmetic. The `vote` table is the source of truth, and a test checks that `vote_count` equals `COUNT(vote)` for every option after a concurrent burst.
- **Edit poll.** The request carries the `version` the client loaded. The `@Version` field on `Poll` makes a stale save return **409** with the message "poll was changed, reload". Removing options uses `DELETE … WHERE id=? AND vote_count=0`; if 0 rows are deleted, the request returns 409. Race between an edit and a vote:
  - If the delete runs first, the vote's increment updates 0 rows and the vote is rejected.
  - If the vote runs first, the delete removes 0 rows and the edit is rejected.
  - Either way, no vote is lost.

## 5. API

All endpoints are under `/api/v1` and use JSON. Errors look like `{status, error, message, fieldErrors?}`. "Bearer" means `Authorization: Bearer <token>` is required.

| Method & path | Auth | Body → Response |
|---|---|---|
| `POST /auth/register` | — | `{username, password}` → 201 `AuthResponse{token, userId, username}`; 409 if the username is taken |
| `POST /auth/login` | — | `{username, password}` → 200 `AuthResponse`; 401 if wrong |
| `POST /polls` | Bearer | `CreatePollDto{question, options:[String]}` → 201 `PollResponse` |
| `GET /polls/{id}` | Bearer, creator | → `PollResponse` |
| `PUT /polls/{id}` | Bearer, creator | `EditPollDto{question, version, options:[{id?, text}]}` → 200 `PollResponse`; 409 if stale or removes an option that has votes |
| `GET /users/me/polls` | Bearer | → `[PollResponse]`, newest first |
| `GET /polls/share/{shareId}` | public | → `PollResponse` (question, status, options with counts, total) |
| `POST /polls/{id}/votes` | Bearer | `{optionId}` → 201 `VoteResponse{pollId, optionId, updatedAt}`; 409 if already voted or closed |
| `PUT /polls/{id}/votes` | Bearer | `{optionId}` → 200 `VoteResponse`; 404 if no vote yet |
| `GET /polls/{id}/votes/me` | Bearer | → 200 `VoteResponse`, or 404 if not voted. *(Added so the UI can pre-select your vote to edit it.)* |

`PollResponse = {id, shareId, question, status, version, creatorUsername, createdAt, updatedAt, totalVotes, options:[{optionId, text, voteCount}]}`

**Validation:**

| Field | Rule |
|---|---|
| username | 3–30 characters of `[A-Za-z0-9_]`, case-insensitive unique |
| password | 8–72 characters (72 is BCrypt's limit) |
| question | 1–300 characters, trimmed |
| options | 2–10 options, each 1–100 characters, trimmed, no duplicates (case-insensitive) |

## 6. UI pages

Thymeleaf shells, `static/js/*.js` and `app.css`.

| Path | Page |
|---|---|
| `/login`, `/register` | Forms. On success, the token is saved and the user is sent back to `?next=` or `/`. |
| `/` | **My polls**: question, total votes, created date, and Open / Edit / Copy link actions. A "New poll" button. Redirects to login if there's no token. |
| `/polls/new` | Question plus a dynamic option list (add/remove, between 2 and 10) |
| `/polls/{id}/edit` | Same form, pre-filled. Shows an inline message on 409 conflicts. |
| `/p/{shareId}` | Question and radio options (your vote pre-selected), then a **Vote** or **Change vote** button, a pie chart with counts and %, and **Copy link** / **Share** buttons. If you're not logged in, the button reads "Log in to vote". |

All pages are responsive, have accessible labels, and show visible error messages. They include the `data-testid` hooks the Playwright tests use.

## 7. Code layout and parallel work split

```
com.example.app
  user/   User, UserRepository
  auth/   AuthToken, AuthTokenRepository | AuthController, AuthService, TokenAuthFilter, SecurityConfig, CurrentUser, dto/
  poll/   Poll, PollOption, PollStatus, PollRepository, PollOptionRepository | PollService, PollController, dto/
  vote/   Vote, VoteRepository | VoteService, VoteController, dto/
  audit/  AuditEvent, AuditEventRepository | AuditService
  web/    PageController (returns templates), ApiExceptionHandler
```

**Step 0 (me, sequential, ~2 min):**
- add `spring-boot-starter-security` and the `playwright` test dependency, plus the `e2e` profile, to `pom.xml`
- freeze the names in §3 to §6 as the contract the agents work from

**Phase 1: 3 agents in parallel.** They share one working tree but each owns separate files, and none of them commits.

| Agent | Owns | Delivers |
|---|---|---|
| **DB** | `db/migration/V1__poll_schema.sql`, all entities + repositories (left of `\|` above) | Schema; entities with `@Version`; repository methods `incrementVoteCount`, `decrementVoteCount`, `deleteIfNoVotes`, `findByUserIdAndPollIdForUpdate`, `findByCreatorIdOrderByCreatedAtDesc`, `findByShareId` |
| **Backend** | everything right of `\|`, plus `web/ApiExceptionHandler`, `application.properties` | DTOs, services (logic from §4), controllers, security config, error handling |
| **UI** | `templates/**`, `static/**`, `web/PageController` | All pages from §6, written against the API contract in §5 |

Then I run `mvn compile`, fix any mismatches between the parts, start the app and run a smoke test with curl.

**Phase 2: 4 agents in parallel**, all against the finished code.

| Agent | Owns | Delivers |
|---|---|---|
| **Unit tests** | `src/test/.../*ServiceTest.java` | Mockito tests of services: validation, ownership 403, edit rules, vote-change logic |
| **Integration tests** | `src/test/.../*IT`-style `@SpringBootTest` classes | MockMvc against in-memory H2: every endpoint + status code, auth, optimistic-lock 409, and a **concurrency test** (e.g. 50 threads voting and changing votes, then assert `vote_count == COUNT(vote)` and the audit rows match) |
| **Playwright** | `src/test/.../e2e/**` | Register → create → copy link → second user votes → pie updates → change vote → edit poll → my-polls list; logged-out view; error cases |
| **Manual test plan** | `docs/MANUAL_TEST_PLAN.md`, `scripts/vote-storm.sh` | Step-by-step cases with expected results and SQL checks (`./scripts/db.sh`), plus a curl script that fires parallel votes so a tester can verify NF2 by hand |

Then I run `mvn test` and `mvn test -Pe2e` and report the actual results.

## 8. Done means

- `mvn test` and `mvn test -Pe2e` are green, and I show you the output.
- The demo works in the browser. User A creates a poll and copies the link. User B opens it, registers, votes and sees the pie. B changes the vote. A edits the poll and sees it in "My polls".
- `./scripts/db.sh "SELECT * FROM audit_event"` shows every action, and `scripts/vote-storm.sh` leaves the counts consistent.

## 9. Out of scope (say this in the interview)

Closing or deleting polls, multi-select votes, email sending, password reset, rate limiting, and real-time updates (the results refresh when you vote or reload, not live through WebSocket).
