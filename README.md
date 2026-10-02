# Poll app

[![CI](https://github.com/HimanshuSingh0495/interview/actions/workflows/ci.yml/badge.svg)](https://github.com/HimanshuSingh0495/interview/actions/workflows/ci.yml)

Create a poll, share it with friends, vote and change your vote, and see the results as a pie chart.

Stack: Java 17 · Spring Boot 3.5 · Spring Security (bearer token + BCrypt) · Spring Data JPA · Flyway · H2 · Thymeleaf shells with vanilla JS · Chart.js · JUnit 5, MockMvc and Playwright.

**Live demo:** _link added after the first deploy_ · [Deploy your own on Render](https://render.com/deploy?repo=https://github.com/HimanshuSingh0495/interview)

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

## Concurrency and integrity

- **No lost votes.** Vote counts change only through SQL arithmetic (`vote_count = vote_count + 1`), never by reading the value, changing it in Java and writing it back. The entity mapping also marks the column as non-updatable.
- **One vote per user per poll.** The database enforces `UNIQUE(user_id, poll_id)`, so a double submit returns 409.
- **Concurrent vote changes are serialised.** Changing a vote locks the user's vote row (`SELECT … FOR UPDATE`). Counters are updated in option-id order, which prevents deadlocks.
- **Concurrent poll edits are caught.** Optimistic locking (`@Version`) rejects a stale edit with 409.
- **Options with votes can't be deleted.** Removing such an option returns 409, and a foreign key from `vote` also blocks the delete at the database level.
- **Audit trail.** Every action (register, create, edit, vote, change vote) is appended to `audit_event` in the same transaction.

Design: [`docs/PLAN.md`](docs/PLAN.md). Implementation contract: [`docs/CONTRACT.md`](docs/CONTRACT.md).

## Database

- **Local file:** `./data/appdb.mv.db`. Delete `data/` to reset.
- **Browser console:** http://localhost:8081/h2-console. Use JDBC URL `jdbc:h2:file:./data/appdb;AUTO_SERVER=TRUE`, user `sa`, and an empty password.
- **Terminal:** `./scripts/db.sh "SELECT * FROM audit_event"`
