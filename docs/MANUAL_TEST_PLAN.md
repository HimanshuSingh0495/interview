# Manual test plan: poll app ("Show of Hands")

This plan lets a tester or interviewer check every claim in `docs/PLAN.md` without reading the code.
Each case lists its purpose, preconditions, steps, the expected result and, where it matters, an exact
`./scripts/db.sh` query with the expected output.

Status codes and messages below were checked against a running build of this code (see §14).

---

## 1. Setup

### 1.1 Start from a clean database

```bash
git clone https://github.com/HimanshuSingh0495/interview.git && cd interview
lsof -ti tcp:8081 -sTCP:LISTEN | xargs kill     # stop any running instance (ignore "usage" output if none)
rm -rf data                                     # reset DB; Flyway recreates the schema on start
mvn spring-boot:run                             # terminal 1: app on http://localhost:8081, SQL is logged here
```

Wait for `Started Application`. In a second terminal (from the project folder):

```bash
./scripts/db.sh        # lists tables: USERS, POLL, POLL_OPTION, VOTE, AUTH_TOKEN, AUDIT_EVENT, flyway_schema_history
```

`./scripts/db.sh` opens `./data/appdb` with `AUTO_SERVER=TRUE`, so it works while the app is running. It only
sees the DB of an app started **from the project folder**. Output is an H2 table followed by `(N rows, x ms)`.

### 1.2 Two users, two browsers

The token lives in `localStorage` (`pollapp.token`, `pollapp.user`), which is per browser profile. Use:

| Role | Browser |
|---|---|
| **User A** (poll owner), username `alice` | normal window, e.g. Chrome |
| **Friend B** (voter), username `bob` | a private/incognito window, or a second browser |

Password for both in this plan: `password123`.

### 1.3 Shell variables for the API cases

The API cases use `curl` and `sed` only (no `jq`). Paste this once per terminal session; later blocks reuse the
variables. It registers fresh users with a random suffix, so it can be re-run.

```bash
BASE=http://localhost:8081/api/v1
J='Content-Type: application/json'
SFX=$RANDOM
tok() { sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }

TOKEN_A=$(curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"alice_$SFX\",\"password\":\"password123\"}" | tok)
TOKEN_B=$(curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"bob_$SFX\",\"password\":\"password123\"}" | tok)

POLL=$(curl -s -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"question":"Where should we eat on Friday?","options":["Tacos","Ramen","Pizza"]}')
echo "$POLL"
POLL_ID=$(echo "$POLL"  | sed -n 's/^{"id":\([0-9]*\),.*/\1/p')
SHARE_ID=$(echo "$POLL" | sed -n 's/.*"shareId":"\([^"]*\)".*/\1/p')
OPT1=$(echo "$POLL" | grep -o '"optionId":[0-9]*' | sed -n '1s/.*://p')
OPT2=$(echo "$POLL" | grep -o '"optionId":[0-9]*' | sed -n '2s/.*://p')
OPT3=$(echo "$POLL" | grep -o '"optionId":[0-9]*' | sed -n '3s/.*://p')
echo "POLL_ID=$POLL_ID SHARE_ID=$SHARE_ID OPT1=$OPT1 OPT2=$OPT2 OPT3=$OPT3"
```

Most cases use `curl -s -w ' %{http_code}\n' ...`, which prints the body followed by the status code.

Every error body has the shape `{"status":N,"error":"<reason>","message":"..."}`, plus `"fieldErrors":[{field,message}]`
on bean-validation 400s.

---

## 2. Traceability

| Req | Requirement | Test cases |
|---|---|---|
| R1 | Create a poll (question + options) | TC-CREATE-01..04, TC-VAL-03, TC-VAL-04 |
| R2 | Edit the poll after creating it | TC-EDIT-01..07, TC-ACL-03 |
| R3 | Send the poll to friends | TC-SHARE-01..04, TC-MY-01 |
| R4 | Vote, and change the vote later | TC-VOTE-01..10, TC-VAL-05 |
| R5 | Register and log in | TC-AUTH-01..08, TC-VAL-01, TC-VAL-02 |
| R6 | Pie chart of the votes | TC-CHART-01..03 |
| R7 | See all my previously created polls | TC-MY-01..03 |
| NF1 | No lost updates on poll edits (optimistic locking) | TC-EDIT-05, TC-EDIT-06, TC-CONC-04, TC-CONC-05 |
| NF2 | No vote lost under concurrency | TC-VOTE-04, TC-VOTE-08, TC-CONC-01..03, TC-CONC-05, TC-CONC-06 |
| NF3 | Every action is auditable | TC-AUD-01..04, TC-AUTH-07 (hashed secrets) |
| §2.5 | Public results, voting needs login, creator-only edit | TC-ACL-01..06, TC-SHARE-03 |
| §2.6 | Voting on a CLOSED poll is refused | TC-VOTE-09 |

---

## 3. Auth (R5)

### TC-AUTH-01 Register in the UI
- **Purpose:** a new user can register and is logged in straight away.
- **Pre:** clean DB, browser A.
- **Steps:**
  1. Open http://localhost:8081. You are redirected to `/login?next=/`.
  2. Click **Create an account** (goes to `/register?next=/`).
  3. Enter username `Alice`, password `password123`, click **Create account**.
- **Expected:** you land on `/` ("My polls"); the header shows `alice` (lower-case), **My polls** and **Log out**; the
  page shows the empty state "No polls yet".
- **DB:** `./scripts/db.sh "SELECT id, username, LEFT(password_hash,7) AS hash_prefix FROM users"`
  → one row, `alice`, `$2a$10$` (BCrypt; the plain password is not stored).

### TC-AUTH-02 Duplicate username (case-insensitive)
- **Pre:** TC-AUTH-01 done. Browser B.
- **Steps:** on `/register` enter `ALICE` / `password123`, submit.
- **Expected:** red box "Username is already taken", username field marked invalid, you stay on the page.
- **API:**
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/auth/register -H "$J" -d "{\"username\":\"ALICE_$SFX\",\"password\":\"password123\"}"
  ```
  → `{"status":409,"error":"Conflict","message":"Username is already taken"} 409`

### TC-AUTH-03 Register form validation (client side)
- **Steps:** on `/register` enter username `ab` and password `short`, submit.
- **Expected:** box "Check the highlighted fields." with "Username: use 3 to 30 letters, numbers or underscores" and
  "Password: use 8 to 72 characters". No request is sent (no `insert into users` in the app log).
- Server-side rules: see TC-VAL-01/02.

### TC-AUTH-04 Log in (trimmed, case-insensitive)
- **Pre:** `alice` exists. Browser A logged out (click **Log out**).
- **Steps:** on `/login` enter ` Alice ` (with spaces) / `password123`, click **Log in**.
- **Expected:** lands on `/`, header shows `alice`.
- **API:**
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/auth/login -H "$J" -d "{\"username\":\" Alice_$SFX \",\"password\":\"password123\"}"
  ```
  → `{"token":"...","userId":N,"username":"alice_NNNN"} 200`

### TC-AUTH-05 Wrong password / unknown user
- **Steps:** log in as `alice` / `wrongpass1`; then as `nobody_here` / `password123`.
- **Expected (both):** red box "Invalid username or password"; same message for both, so usernames can't be probed.
- **API:**
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/auth/login -H "$J" -d "{\"username\":\"alice_$SFX\",\"password\":\"wrong-pass\"}"
  ```
  → `{"status":401,"error":"Unauthorized","message":"Invalid username or password"} 401`

### TC-AUTH-06 Log out
- **Steps:** click **Log out** in the header, then open http://localhost:8081/ and http://localhost:8081/polls/new.
- **Expected:** after logout you are on `/login`; the header shows **Log in** only. Both pages redirect to
  `/login?next=/` and `/login?next=/polls/new`.
- **Note:** logout only clears `localStorage`; the token stays valid on the server until it expires (see §13).

### TC-AUTH-07 Tokens are stored hashed with a 7-day expiry
- **Steps:** after TC-AUTH-04, `./scripts/db.sh "SELECT user_id, LENGTH(token_hash) AS len, DATEDIFF('DAY', created_at, expires_at) AS days FROM auth_token"`
- **Expected:** one row per login/registration, `len` = 64 (SHA-256 hex), `days` = 7. Compare with
  `localStorage["pollapp.token"]` in DevTools: the raw token (43 chars, base64url) does not appear in the table.

### TC-AUTH-08 Login keeps `?next=`
- **Pre:** a poll link `/p/{shareId}` (from TC-CREATE-01). Browser B logged out.
- **Steps:** open `http://localhost:8081/login?next=/p/{shareId}`, click **Create an account**, register `bob`.
- **Expected:** the register link carries `?next=/p/{shareId}`; after registering you land on the poll page.
  A malicious `?next=//evil.com` or `?next=https://evil.com` sends you to `/` instead.

---

## 4. Create poll (R1)

### TC-CREATE-01 Create a poll in the UI
- **Pre:** browser A logged in as `alice`.
- **Steps:**
  1. On `/` click **New poll** (`/polls/new`). Two empty answer rows are shown; their remove (×) buttons are disabled.
  2. Question `Where should we eat on Friday?`; Answer 1 `Tacos`, Answer 2 `Ramen`; click **Add answer**, Answer 3 `Pizza`.
  3. Click **Create poll**.
- **Expected:** redirected to `/p/{shareId}` (10 random letters/digits). The page shows "Asked by alice", the question,
  three radio options, a disabled **Vote** button, "0 votes" and the "No votes yet" chart placeholder.
- **DB:**
  ```bash
  ./scripts/db.sh "SELECT id, share_id, question, status, version FROM poll"
  ./scripts/db.sh "SELECT id, poll_id, option_text, position, vote_count FROM poll_option ORDER BY position"
  ```
  → 1 poll, `status=OPEN`, `version=0`; 3 options, positions 0,1,2, `vote_count=0`.

### TC-CREATE-02 Answer list bounds (2..10)
- **Steps:** on `/polls/new` click **Add answer** until it reads **Maximum of 10 answers** (disabled). Remove rows with ×
  until 2 are left.
- **Expected:** exactly 10 rows max; with 2 rows both × buttons are disabled ("A poll needs at least 2 answers"
  tooltip). The question counter shows `n / 300`; inputs stop at 300 / 100 characters.

### TC-CREATE-03 Client-side checks
- **Steps:** leave the question blank, type `Tacos` in both answers, click **Create poll**.
- **Expected:** "Check the highlighted fields." with "Question: enter a question" and "Answer 2: this answer is a
  duplicate". Nothing is saved.

### TC-CREATE-04 API create, trimming and response shape
```bash
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d '{"question":"  Lunch?  ","options":[" Tacos ","Ramen"]}'
```
- **Expected:** `201`, body `{"id":N,"shareId":"<10 chars>","question":"Lunch?","status":"OPEN","version":0,
  "creatorUsername":"alice_NNNN","createdAt":...,"updatedAt":...,"totalVotes":0,"options":[{"optionId":..,"text":"Tacos","voteCount":0},{..."Ramen"...}]}`
  (question and options trimmed).

---

## 5. Edit poll (R2, NF1)

### TC-EDIT-01 Edit in the UI: rename and add
- **Pre:** TC-CREATE-01 poll; B has voted for `Ramen` (TC-VOTE-01).
- **Steps:**
  1. Browser A: on the poll page click **Edit poll** (or **Edit** on `/`). URL `/polls/{id}/edit`.
  2. Change question to `Where should we eat on Saturday?`, rename `Ramen` to `Ramen bowl`, **Add answer** `Sushi`.
  3. Click **Save changes**.
- **Expected:** redirected to the poll page with the new question and four options; `Ramen bowl` still has **1 vote**.
- **DB:**
  ```bash
  ./scripts/db.sh "SELECT id, question, version FROM poll"
  ./scripts/db.sh "SELECT id, option_text, position, vote_count FROM poll_option ORDER BY position"
  ```
  → `version` went from 0 to 1; the Ramen row kept its **id** and `vote_count=1`; a new `Sushi` row.

### TC-EDIT-02 Options with votes can't be removed in the UI
- **Pre:** as TC-EDIT-01.
- **Steps:** open the edit page.
- **Expected:** each existing answer shows its vote count; the voted one reads "1 vote, so it can't be removed. You
  can still reword it." and its × is disabled. 0-vote answers show "0 votes" and can be removed.

### TC-EDIT-03 API: removing an option that has votes → 409
- **Pre:** §1.3 variables; B voted for `OPT2`:
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT2}"
  ```
- **Steps:** (current version from `curl -s $BASE/polls/share/$SHARE_ID`; 0 if unedited)
  ```bash
  curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
    -d "{\"question\":\"Where should we eat on Friday?\",\"version\":0,\"options\":[{\"id\":$OPT1,\"text\":\"Tacos\"},{\"id\":$OPT3,\"text\":\"Pizza\"}]}"
  ```
- **Expected:** `{"status":409,"error":"Conflict","message":"Cannot remove option 'Ramen': it has votes"} 409`.
  Nothing changed: `curl -s $BASE/polls/share/$SHARE_ID` still shows 3 options, `version` 0, Ramen `voteCount` 1.

### TC-EDIT-04 API: remove a 0-vote option, rename one, add one
```bash
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"Lunch Friday?\",\"version\":0,\"options\":[{\"id\":$OPT2,\"text\":\"Ramen bowl\"},{\"id\":$OPT3,\"text\":\"Pizza\"},{\"text\":\"Sushi\"}]}"
```
- **Expected:** `200`, `"version":1`, options `Ramen bowl` (same optionId, voteCount 1), `Pizza`, `Sushi` (new id);
  `Tacos` is gone.
- **DB:** `./scripts/db.sh "SELECT id, option_text, position, vote_count FROM poll_option WHERE poll_id=$POLL_ID ORDER BY position"`
  (substitute the number) → 3 rows; no row with id `OPT1`.

### TC-EDIT-05 API: stale version → 409 (optimistic locking)
- **Pre:** TC-EDIT-04 done (version is now 1).
```bash
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"Overwrite?\",\"version\":0,\"options\":[{\"id\":$OPT2,\"text\":\"Ramen\"},{\"id\":$OPT3,\"text\":\"Pizza\"}]}"
```
- **Expected:** `{"status":409,"error":"Conflict","message":"Poll was changed by someone else, reload and try again"} 409`;
  question is still `Lunch Friday?`.

### TC-EDIT-06 UI: two tabs editing the same poll
- **Steps:**
  1. Browser A: open `/polls/{id}/edit` in **two tabs**.
  2. Tab 1: change the question, **Save changes** (succeeds).
  3. Tab 2 (still showing the old version): change an answer, **Save changes**.
- **Expected:** tab 2 shows the red box "Poll was changed by someone else, reload and try again" with a **Reload**
  button; tab 1's change is kept. Clicking **Reload** loads the latest version.

### TC-EDIT-07 API: bad option ids in an edit
```bash
# option id from another poll (or any unknown id) -> 404
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"q\",\"version\":1,\"options\":[{\"id\":999999,\"text\":\"x\"},{\"id\":$OPT3,\"text\":\"Pizza\"}]}"
# the same id twice -> 400
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"q\",\"version\":1,\"options\":[{\"id\":$OPT3,\"text\":\"A\"},{\"id\":$OPT3,\"text\":\"B\"}]}"
```
- **Expected:** `{"status":404,"error":"Not Found","message":"Option not found in this poll"} 404`, then
  `{"status":400,"error":"Bad Request","message":"The same option is listed twice"} 400`.

---

## 6. Share (R3)

### TC-SHARE-01 Copy link
- **Steps:** on the poll page click **Copy link**; on `/` click **Copy link** in a poll row. Paste into the address bar.
- **Expected:** toast "Link copied"; the clipboard holds `http://localhost:8081/p/{shareId}`.

### TC-SHARE-02 Share button
- **Steps:** click **Share** on the poll page.
- **Expected:** where the Web Share API exists (Safari, mobile, Chrome on macOS) the system share sheet opens with the
  question and link. Otherwise the mail client opens a `mailto:` with subject `Vote: <question>` and the link in the body.
  No email is sent by the server.

### TC-SHARE-03 Friend opens the link logged out
- **Pre:** browser B logged out (private window).
- **Steps:** open the copied link.
- **Expected:** question, options (radios disabled), results and pie chart are visible without logging in. The vote
  button is replaced by **Log in to vote** (`/login?next=/p/{shareId}`). No **Edit poll** link. Following it and
  registering/logging in returns to the poll with a **Vote** button.

### TC-SHARE-04 Unknown share id
- **Steps:** open http://localhost:8081/p/doesnotexist.
- **Expected:** page shows "This poll doesn't exist. Check the link you were sent." and **Go to my polls**.
- **API:** `curl -s -w ' %{http_code}\n' $BASE/polls/share/doesnotexist` → `{"status":404,"error":"Not Found","message":"Poll not found"} 404`

---

## 7. Vote and change vote (R4)

### TC-VOTE-01 Vote in the UI
- **Pre:** browser B logged in as `bob`, on A's poll page.
- **Steps:** the **Vote** button is disabled until an answer is picked. Pick `Ramen`, click **Vote**.
- **Expected:** status "Vote saved"; button now reads **Change vote**; `Ramen` is tagged "Your vote" in the ballot and the
  results; total "1 vote"; the pie shows one full slice.
- **DB:**
  ```bash
  ./scripts/db.sh "SELECT v.id, u.username, o.option_text FROM vote v JOIN users u ON u.id=v.user_id JOIN poll_option o ON o.id=v.option_id"
  ./scripts/db.sh "SELECT option_text, vote_count FROM poll_option ORDER BY position"
  ```
  → one vote row `bob / Ramen`; Ramen `vote_count=1`.

### TC-VOTE-02 Change the vote in the UI
- **Steps:** pick `Pizza`, click **Change vote**.
- **Expected:** "Vote changed"; Ramen 0, Pizza 1; total still 1.
- **DB:** same queries → still **one** vote row (same `id`), now `Pizza`; Ramen 0, Pizza 1.
  `./scripts/db.sh "SELECT action, details FROM audit_event WHERE action LIKE 'VOTE%' ORDER BY id"` →
  `VOTE_CAST option=<ramenId>`, then `VOTE_CHANGED option <ramenId> -> <pizzaId>`.

### TC-VOTE-03 Your vote is remembered
- **Steps:** reload the page in browser B; also log out and back in.
- **Expected:** `Pizza` is pre-selected and tagged "Your vote"; the button reads **Change vote**.
- **API:** `curl -s -w ' %{http_code}\n' -H "Authorization: Bearer $TOKEN_B" $BASE/polls/$POLL_ID/votes/me`
  → `{"pollId":..,"optionId":..,"updatedAt":".."} 200` (404 "You have not voted on this poll yet" before voting).

### TC-VOTE-04 API: duplicate POST → 409
- **Pre:** B has voted (TC-EDIT-03 pre step, or):
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT2}"
  ```
- **Steps:** POST again (any option):
  ```bash
  curl -s -w ' %{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT3}"
  ```
- **Expected:** first `{"pollId":..,"optionId":..,"updatedAt":".."} 201`; second
  `{"status":409,"error":"Conflict","message":"You already voted on this poll; use PUT to change your vote"} 409`.
  `totalVotes` did not change.

### TC-VOTE-05 API: change before voting → 404
```bash
TOKEN_C=$(curl -s -X POST $BASE/auth/register -H "$J" -d "{\"username\":\"carol_$SFX\",\"password\":\"password123\"}" | tok)
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_C" -H "$J" -d "{\"optionId\":$OPT2}"
```
- **Expected:** `{"status":404,"error":"Not Found","message":"You have not voted on this poll yet"} 404`

### TC-VOTE-06 API: change vote, and "change" to the same option
```bash
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT3}"
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT3}"
curl -s $BASE/polls/share/$SHARE_ID; echo
```
- **Expected:** both PUTs `200` with `"optionId":<OPT3>`; the second returns the same `updatedAt` (no-op). Counts:
  OPT3 has 1, the old option 0, `totalVotes` 1. Only **one** `VOTE_CHANGED` audit row is added.

### TC-VOTE-07 Vote needs an option
- **UI:** with nothing selected the **Vote** button is disabled.
- **API:** `curl -s -w ' %{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_C" -H "$J" -d '{}'`
  → `{"status":400,"error":"Bad Request","message":"Validation failed","fieldErrors":[{"field":"optionId","message":"Pick an option"}]} 400`

### TC-VOTE-08 Two tabs, same user (UI double submit)
- **Pre:** a user who has **not** voted yet; open the poll page in two tabs.
- **Steps:** tab 1: pick an answer, **Vote**. Tab 2 (still shows **Vote**): pick another answer, **Vote**.
- **Expected:** tab 2 shows "You already voted on this poll; use PUT to change your vote Your earlier vote is selected;
  use Change vote to switch.", re-syncs to the tab-1 choice, button becomes **Change vote**. Total went up by exactly 1.

### TC-VOTE-09 Closed poll refuses votes (no UI to close; set it in the DB)
- **Steps:**
  ```bash
  ./scripts/db.sh "UPDATE poll SET status='CLOSED' WHERE id=<POLL_ID>"
  curl -s -w ' %{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_C" -H "$J" -d "{\"optionId\":$OPT3}"
  curl -s -w ' %{http_code}\n' -X PUT  $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$OPT2}"
  ```
- **Expected:** both `{"status":409,"error":"Conflict","message":"This poll is closed"} 409`. In the browser the page
  shows a **Closed** badge, disabled radios and "This poll is closed. You can still see the results."; the row on `/`
  has a **Closed** badge. Re-open with `UPDATE poll SET status='OPEN' WHERE id=<POLL_ID>`.

### TC-VOTE-10 The owner can vote on their own poll
- **Steps:** browser A votes on its own poll.
- **Expected:** "Vote saved"; counts include A's vote (one vote per user, owner included).

---

## 8. Results chart (R6)

### TC-CHART-01 Empty poll
- **Expected:** on a new poll the chart area shows "No votes yet / The chart fills in as people vote.", the total reads
  "0 votes", each result row "0 votes 0%".

### TC-CHART-02 Chart matches the data
- **Pre:** 3+ users voted on different answers (e.g. after TC-CONC-01 open the printed poll URL).
- **Expected:** a pie with one coloured slice per answer that has votes; the swatch colours in the ballot and result list
  match the slices; each result row shows "N votes" and a rounded %; hovering a slice shows "N votes (x%)".
  The numbers equal `curl -s $BASE/polls/share/$SHARE_ID` and
  `./scripts/db.sh "SELECT option_text, vote_count FROM poll_option WHERE poll_id=<POLL_ID> ORDER BY position"`.

### TC-CHART-03 Refresh behaviour
- **Steps:** B votes; then A (other browser) looks at the same page without reloading; then reloads.
- **Expected:** B's own chart redraws immediately after voting. A sees the new count only after reload (no real-time
  push; out of scope).

---

## 9. My polls (R7)

### TC-MY-01 List of my polls
- **Pre:** A created 2+ polls (create a second one at `/polls/new`).
- **Expected:** `/` lists them **newest first**; each row shows the question (link to `/p/{shareId}`), "N votes",
  "Created <date>", and **Open**, **Edit**, **Copy link**.
- **API:** `curl -s -w ' %{http_code}\n' -H "Authorization: Bearer $TOKEN_A" $BASE/users/me/polls` → `200`, JSON array,
  newest `createdAt` first.
- **DB:** `./scripts/db.sh "SELECT p.id, p.question, p.created_at FROM poll p JOIN users u ON u.id=p.creator_id WHERE u.username='alice' ORDER BY p.created_at DESC"`
  → same order and count as the page.

### TC-MY-02 Empty state
- **Steps:** log in as a user with no polls (e.g. `bob`).
- **Expected:** "No polls yet" and **Create your first poll**. API returns `[] 200`.

### TC-MY-03 Only my polls
- **Expected:** B's list never contains A's polls, even ones B voted on.

---

## 10. Access control (§2.5)

### TC-ACL-01 No token → 401 on every protected endpoint
```bash
for req in "GET $BASE/users/me/polls" "GET $BASE/polls/$POLL_ID" "POST $BASE/polls" \
           "PUT $BASE/polls/$POLL_ID" "POST $BASE/polls/$POLL_ID/votes" "PUT $BASE/polls/$POLL_ID/votes" \
           "GET $BASE/polls/$POLL_ID/votes/me"; do
  set -- $req; printf '%-5s %-45s ' $1 ${2#$BASE}
  curl -s -w ' %{http_code}\n' -X $1 $2 -H "$J" -d '{}' -o /dev/null
done
curl -s -w ' %{http_code}\n' $BASE/users/me/polls     # shows the body once
```
- **Expected:** every line ends in `401`; the body is `{"status":401,"error":"Unauthorized","message":"Authentication required"}`.

### TC-ACL-02 Invalid token → 401
`curl -s -w ' %{http_code}\n' -H 'Authorization: Bearer not-a-real-token' $BASE/users/me/polls`
→ `{"status":401,"error":"Unauthorized","message":"Authentication required"} 401`.
In the UI: put a bogus value in `localStorage["pollapp.token"]` and open `/`; you are sent to `/login?next=/`.

### TC-ACL-03 Only the creator can read/edit the poll by id
```bash
curl -s -w ' %{http_code}\n' -H "Authorization: Bearer $TOKEN_B" $BASE/polls/$POLL_ID
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_B" -H "$J" \
  -d "{\"question\":\"hack\",\"version\":0,\"options\":[{\"id\":$OPT2,\"text\":\"a\"},{\"id\":$OPT3,\"text\":\"b\"}]}"
curl -s -w ' %{http_code}\n' -H "Authorization: Bearer $TOKEN_A" $BASE/polls/999999
```
- **Expected:** `{"status":403,"error":"Forbidden","message":"Only the creator of this poll can do that"} 403` twice,
  then `{"status":404,"error":"Not Found","message":"Poll not found"} 404`.
- **UI:** browser B opens `/polls/{A's poll id}/edit` → "Only the person who created this poll can edit it." and **Back to my polls**.

### TC-ACL-04 Results are public
`curl -s -w ' %{http_code}\n' $BASE/polls/share/$SHARE_ID` (no token) → `200` with the poll and counts.

### TC-ACL-05 Pages that need login redirect
- **Steps:** logged out, open `/`, `/polls/new`, `/polls/1/edit`.
- **Expected:** each redirects to `/login?next=<that path>`; after login you return there.

### TC-ACL-06 Edit link only for the owner
- **Expected:** on `/p/{shareId}` the **Edit poll** link is visible to A only, not to B or logged-out visitors.

---

## 11. Validation (server side)

All return `400` with `"message":"Validation failed"` and `fieldErrors`, unless stated otherwise.

| ID | Request | Expected `fieldErrors` / message |
|---|---|---|
| TC-VAL-01 | register `{"username":"ab",...}`, `"bad-name"`, or 31 chars | `username`: "Username must be 3-30 letters, digits or underscores" |
| TC-VAL-02 | register password `short` or 73 chars | `password`: "Password must be 8-72 characters" |
| TC-VAL-03 | create, question `"   "` / 301 chars | `question`: "Question is required" / "Question must be 300 characters or fewer" |
| TC-VAL-04a | create, 1 option or 11 options | `options`: "A poll needs 2-10 options" |
| TC-VAL-04b | create, option `"  "` / 101 chars | `options[1]`: "Option text is required" / "Option must be 100 characters or fewer" |
| TC-VAL-04c | create, options `["a"," A "]` | no fieldErrors; message "Options must be unique (ignoring case)" |
| TC-VAL-05a | vote body `{}` | `optionId`: "Pick an option" |
| TC-VAL-05b | vote for an option of another poll | **404** "Option not found in this poll" |
| TC-VAL-05c | vote on poll `999999` / poll `abc` | **404** "Poll not found" / **400** "Invalid value for 'pollId'" |
| TC-VAL-05d | malformed JSON body `{"question":` | 400 "Malformed JSON request body" (no fieldErrors) |
| TC-VAL-06 | edit without `version` | `version`: "Version is required" |

Copy-paste:
```bash
curl -s -w ' %{http_code}\n' -X POST $BASE/auth/register -H "$J" -d '{"username":"ab","password":"short"}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":"   ","options":["a","b"]}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":"x","options":["a"]}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":"x","options":["a","  "]}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":"x","options":["a"," A "]}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls/abc/votes -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"optionId":1}'
curl -s -w ' %{http_code}\n' -X POST $BASE/polls/999999/votes -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"optionId":1}'
curl -s -w ' %{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"q\",\"options\":[{\"id\":$OPT2,\"text\":\"a\"},{\"id\":$OPT3,\"text\":\"b\"}]}"
```

---

## 12. Concurrency and data integrity (NF1, NF2)

**The consistency query.** Every counter must equal the number of vote rows pointing at it:

```bash
./scripts/db.sh "SELECT o.id, o.vote_count, COUNT(v.id) FROM poll_option o LEFT JOIN vote v ON v.option_id=o.id GROUP BY o.id, o.vote_count HAVING o.vote_count <> COUNT(v.id)"
```
Expected: the header line then `(0 rows, ...)`.

### TC-CONC-01 Vote storm script
- **Purpose:** many users voting and changing votes at the same moment lose and duplicate nothing.
- **Pre:** app running on 8081. The script adds users named `s<timestamp>_<rand>_owner` / `_v1.._vN` and a poll
  `Vote storm ...` to your DB (reset with `rm -rf data` afterwards if you want a clean demo).
- **Run:**
  ```bash
  ./scripts/vote-storm.sh                              # http://localhost:8081, 30 voters
  ./scripts/vote-storm.sh http://localhost:8081 100    # more voters
  PARALLEL=20 PUTS_PER_USER=5 ./scripts/vote-storm.sh  # optional tuning (defaults 50 and 3)
  ```
- **What it does:**
  1. registers an owner and creates a poll with options Red / Green / Blue;
  2. registers `USERS` voters in parallel;
  3. fires every voter's `POST /votes` **twice**, shuffled, `PARALLEL` at a time (random option each);
  4. fires `PUTS_PER_USER` `PUT /votes` per voter to random options, shuffled and in parallel;
  5. reads `GET /api/v1/polls/share/{shareId}`.
- **Expected:** step 3 gives exactly `USERS` × 201 and `USERS` × 409; step 4 all 200; `totalVotes == USERS`; no 5xx;
  last line `PASS: ...`, exit code 0. Anything else prints `FAIL: ...` and exits 1.
- **Real output** (30 voters, against a throwaway instance of this build):
  ```
  == Vote storm against http://localhost:9311 with 30 voters (run id s1790957615_9132)
  1. Created poll id=1 shareId=2q0mkxh5At options=1 2 3
  2. Registered 30 voters
  3. POST x2 per voter (60 requests): 201=30 409=30 other=0   (expect 201=30 409=30)
  4. PUT x3 per voter (90 requests): 200=90 other=0   (expect 200=90)
  5. Results from GET /api/v1/polls/share/2q0mkxh5At:
     option 1 (Red): 11
     option 2 (Green): 8
     option 3 (Blue): 11
     totalVotes: 30   (expect 30)

  Status codes over all vote requests: 201=30 200=90 409=30 other=0 (5xx=0)

  Poll page (open it to see the pie chart): http://localhost:9311/p/2q0mkxh5At
  shareId: 2q0mkxh5At   pollId: 1

  Next, check the stored counters match the vote rows (must return 0 rows):
    ./scripts/db.sh "SELECT o.id, o.vote_count, COUNT(v.id) FROM poll_option o LEFT JOIN vote v ON v.option_id=o.id GROUP BY o.id, o.vote_count HAVING o.vote_count <> COUNT(v.id)"
  And the audit trail for this poll (expect 30 VOTE_CAST, up to 90 VOTE_CHANGED; a PUT to the option you already have is not audited):
    ./scripts/db.sh "SELECT action, COUNT(*) FROM audit_event WHERE poll_id=1 GROUP BY action"

  PASS: totalVotes=30 == USERS=30, 30 votes created, no 5xx
  ```
  The per-option split is random; only the totals are fixed.
- **Then:** open the printed poll URL (pie chart, TC-CHART-02) and run the two printed queries (TC-CONC-02, TC-CONC-06).

### TC-CONC-02 Counters equal vote rows
- **Steps:** run the consistency query above after TC-CONC-01 (and after any other case).
- **Expected:** `(0 rows, ...)`. Also `./scripts/db.sh "SELECT COUNT(*) FROM vote WHERE poll_id=<pollId>"` = `USERS`.

### TC-CONC-03 Same user, two POSTs at the same instant
```bash
TOKEN_D=$(curl -s -X POST $BASE/auth/register -H "$J" -d "{\"username\":\"dave_$SFX\",\"password\":\"password123\"}" | tok)
for o in $OPT2 $OPT3; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/polls/$POLL_ID/votes -H "Authorization: Bearer $TOKEN_D" -H "$J" -d "{\"optionId\":$o}" &
done; wait
```
- **Expected:** one `201` and one `409` (either order). `totalVotes` rose by exactly 1; the consistency query returns 0 rows.

### TC-CONC-04 Concurrent edits with the same version (NF1)
```bash
V=$(curl -s $BASE/polls/share/$SHARE_ID | sed -n 's/.*"version":\([0-9]*\).*/\1/p')
for t in 1 2 3 4 5 6 7 8 9 10; do
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT $BASE/polls/$POLL_ID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
    -d "{\"question\":\"Edit $t\",\"version\":$V,\"options\":[{\"id\":$OPT2,\"text\":\"Ramen $t\"},{\"id\":$OPT3,\"text\":\"Pizza\"}]}" &
done; wait
curl -s $BASE/polls/share/$SHARE_ID; echo
```
(If TC-EDIT-04 removed `OPT1`, the list above uses only OPT2/OPT3. If OPT1 still exists **and has 0 votes** it is
deleted by the winning edit; include it as `{"id":$OPT1,"text":"Tacos"}` to keep it.)
- **Expected:** exactly **one** `200` and nine `409` ("Poll was changed by someone else, reload and try again").
  The final question `Edit N` and option text `Ramen N` come from the same request (no mix of two edits);
  `version` = `V+1`; one new `POLL_UPDATED` audit row.

### TC-CONC-05 Edit removing an option vs. a vote on that option
- **Purpose:** PLAN §4: whichever runs first wins; the vote is never lost.
```bash
P=$(curl -s -X POST $BASE/polls -H "Authorization: Bearer $TOKEN_A" -H "$J" -d '{"question":"race","options":["a","b","c"]}')
PID=$(echo "$P" | sed -n 's/^{"id":\([0-9]*\),.*/\1/p')
O1=$(echo "$P" | grep -o '"optionId":[0-9]*' | sed -n '1s/.*://p')
O2=$(echo "$P" | grep -o '"optionId":[0-9]*' | sed -n '2s/.*://p')
O3=$(echo "$P" | grep -o '"optionId":[0-9]*' | sed -n '3s/.*://p')
curl -s -w ' vote=%{http_code}\n' -X POST $BASE/polls/$PID/votes -H "Authorization: Bearer $TOKEN_B" -H "$J" -d "{\"optionId\":$O3}" &
curl -s -w ' edit=%{http_code}\n' -X PUT $BASE/polls/$PID -H "Authorization: Bearer $TOKEN_A" -H "$J" \
  -d "{\"question\":\"race\",\"version\":0,\"options\":[{\"id\":$O1,\"text\":\"a\"},{\"id\":$O2,\"text\":\"b\"}]}" &
wait
```
- **Expected:** exactly one wins. Either `vote=201` and `edit=409` "Cannot remove option 'c': it has votes" (most runs),
  or `edit=200` and `vote=409` "Option no longer exists" (or `404` "Option not found in this poll" if the edit committed
  before the vote started). Never both 2xx. Run it several times; the consistency query always returns 0 rows.

### TC-CONC-06 Audit agrees with the vote table
```bash
./scripts/db.sh "SELECT a.poll_id, COUNT(*) AS cast_events, (SELECT COUNT(*) FROM vote v WHERE v.poll_id=a.poll_id) AS vote_rows FROM audit_event a WHERE a.action='VOTE_CAST' GROUP BY a.poll_id HAVING COUNT(*) <> (SELECT COUNT(*) FROM vote v WHERE v.poll_id=a.poll_id)"
```
- **Expected:** `(0 rows, ...)`: one `VOTE_CAST` per vote row, and the rejected duplicate POSTs (409) left no audit row.

### TC-CONC-07 DB safety net (FK)
```bash
./scripts/db.sh "DELETE FROM poll_option WHERE id=(SELECT MIN(option_id) FROM vote)"
```
- **Expected:** the shell prints a referential-integrity error mentioning `FK_VOTE_OPTION`; nothing is deleted.

---

## 13. Audit trail (NF3)

### TC-AUD-01 Every action type is recorded
- **Pre:** cases from §3 to §7 done (register, create, edit, vote, change).
- **Steps:**
  ```bash
  ./scripts/db.sh "SELECT action, COUNT(*) FROM audit_event GROUP BY action ORDER BY action"
  ./scripts/db.sh "SELECT a.id, u.username AS actor, a.action, a.poll_id, a.details, a.created_at FROM audit_event a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id"
  ```
- **Expected:** all five actions appear: `USER_REGISTERED`, `POLL_CREATED`, `POLL_UPDATED`, `VOTE_CAST`, `VOTE_CHANGED`.
  Example rows (real, from the verification run):
  ```
  ACTOR       | ACTION          | POLL_ID | DETAILS
  alice_23180 | USER_REGISTERED | null    | username=alice_23180
  alice_23180 | POLL_CREATED    | 3       | 3 options
  bob_23180   | VOTE_CAST       | 3       | option=7
  alice_23180 | POLL_UPDATED    | 3       | question changed; kept=3, renamed=1, added=0, removed=0
  ```
  `VOTE_CHANGED` details read `option <old> -> <new>`.

### TC-AUD-02 Full vote history of one user
```bash
./scripts/db.sh "SELECT a.id, a.action, a.details, a.created_at FROM audit_event a JOIN users u ON u.id=a.actor_id WHERE u.username='bob' AND a.action LIKE 'VOTE%' ORDER BY a.id"
```
- **Expected:** after TC-VOTE-01/02: `VOTE_CAST option=X`, then `VOTE_CHANGED option X -> Y`, while the `vote` table
  holds only the current row (`Y`). This is the history the `UNIQUE(user, poll)` vote table cannot keep.

### TC-AUD-03 Failed actions leave no audit row
- **Steps:** note `./scripts/db.sh "SELECT COUNT(*) FROM audit_event"`; run TC-VOTE-04's second POST (409),
  TC-EDIT-03 (409) and TC-EDIT-05 (409); count again.
- **Expected:** the count is unchanged (the audit insert is in the same transaction and rolls back).

### TC-AUD-04 Audit is append-only from the app's side
- **Expected:** no API endpoint changes or deletes `audit_event` rows; ids only increase. (Not enforced in the DB; see §14.)

---

## 14. Known limitations / out of scope

From PLAN §9 and the decisions in §2:

- No closing or deleting polls in the API/UI. `status=CLOSED` exists and is enforced on votes (TC-VOTE-09), but can only
  be set in the DB. A closed poll can still be edited by its owner.
- Single-choice votes only; no multi-select.
- No email sending: **Share** uses the Web Share API or a `mailto:` link.
- No password reset, no rate limiting, no account lockout.
- No real-time updates: results refresh after your own vote or a page reload (TC-CHART-03).
- The share route is `GET /api/v1/polls/share/{shareId}` (the spec's `/polls/{sharableId}` clashed with `/polls/{id}`).
- Auth is an opaque bearer token (SHA-256 stored, 7-day TTL), kept in `localStorage`. **Logout is client-side only**:
  the token is not revoked server-side and stays valid until it expires.
- Results are public to anyone with the link; voting needs an account.
- Audit covers register, create, edit, cast and change. **Logins and rejected attempts are not audited**; a PUT to the
  option you already have is a no-op and not audited. `audit_event` is append-only by convention (no DB trigger).
- Chart.js is vendored at `/vendor/chart.umd.min.js` (PLAN §2.8 says CDN; the app works offline).
- Unmapped methods (e.g. `DELETE /api/v1/polls/1`) return Spring's default 405 body
  (`{"timestamp",...,"path"}`), not the app's `{status,error,message}` shape.

### Verification log for this document
Run against a throwaway instance of this code (port 9311, separate DB), not the developer's 8081 instance:
- `vote-storm.sh` with 30 and 100 voters: PASS both; consistency query 0 rows; `VOTE_CAST` = 100 for 200 POSTs.
- Checked responses for: TC-AUTH-02, 04, 05; TC-CREATE-04; TC-EDIT-03, 04, 05, 07; TC-SHARE-04; TC-VOTE-03..07, 09;
  TC-ACL-01 (no/invalid token), 03, 04; TC-VAL-01..06; TC-CONC-03, 04 (1×200, 9×409), 05 (15 rounds: 14× vote wins,
  1× edit wins); TC-AUD-01 queries; TC-AUTH-07 query.
- UI cases (marked with browser steps) were written from the templates and JS, not clicked through.

---

## 15. Results checklist

| ID | Title | Pass | Fail | Notes |
|---|---|:-:|:-:|---|
| TC-AUTH-01 | Register in the UI | ☐ | ☐ | |
| TC-AUTH-02 | Duplicate username 409 | ☐ | ☐ | |
| TC-AUTH-03 | Register client-side validation | ☐ | ☐ | |
| TC-AUTH-04 | Log in, trimmed + case-insensitive | ☐ | ☐ | |
| TC-AUTH-05 | Wrong password 401 | ☐ | ☐ | |
| TC-AUTH-06 | Log out + redirects | ☐ | ☐ | |
| TC-AUTH-07 | Token stored hashed, 7 days | ☐ | ☐ | |
| TC-AUTH-08 | `?next=` kept, unsafe next ignored | ☐ | ☐ | |
| TC-CREATE-01 | Create poll in UI | ☐ | ☐ | |
| TC-CREATE-02 | 2..10 answers | ☐ | ☐ | |
| TC-CREATE-03 | Client-side checks | ☐ | ☐ | |
| TC-CREATE-04 | API create + trimming | ☐ | ☐ | |
| TC-EDIT-01 | UI rename + add, votes kept | ☐ | ☐ | |
| TC-EDIT-02 | Voted option locked in UI | ☐ | ☐ | |
| TC-EDIT-03 | Remove voted option 409 | ☐ | ☐ | |
| TC-EDIT-04 | Remove 0-vote / rename / add | ☐ | ☐ | |
| TC-EDIT-05 | Stale version 409 | ☐ | ☐ | |
| TC-EDIT-06 | Two tabs editing, Reload | ☐ | ☐ | |
| TC-EDIT-07 | Bad option ids 404/400 | ☐ | ☐ | |
| TC-SHARE-01 | Copy link | ☐ | ☐ | |
| TC-SHARE-02 | Share button | ☐ | ☐ | |
| TC-SHARE-03 | Logged-out friend | ☐ | ☐ | |
| TC-SHARE-04 | Unknown share id | ☐ | ☐ | |
| TC-VOTE-01 | Vote in UI | ☐ | ☐ | |
| TC-VOTE-02 | Change vote in UI | ☐ | ☐ | |
| TC-VOTE-03 | Vote remembered | ☐ | ☐ | |
| TC-VOTE-04 | Duplicate POST 409 | ☐ | ☐ | |
| TC-VOTE-05 | PUT before vote 404 | ☐ | ☐ | |
| TC-VOTE-06 | Change + same-option no-op | ☐ | ☐ | |
| TC-VOTE-07 | Vote needs option | ☐ | ☐ | |
| TC-VOTE-08 | Two tabs voting | ☐ | ☐ | |
| TC-VOTE-09 | Closed poll 409 | ☐ | ☐ | |
| TC-VOTE-10 | Owner can vote | ☐ | ☐ | |
| TC-CHART-01 | Empty chart | ☐ | ☐ | |
| TC-CHART-02 | Chart matches data | ☐ | ☐ | |
| TC-CHART-03 | Refresh behaviour | ☐ | ☐ | |
| TC-MY-01 | My polls, newest first | ☐ | ☐ | |
| TC-MY-02 | Empty state | ☐ | ☐ | |
| TC-MY-03 | Only my polls | ☐ | ☐ | |
| TC-ACL-01 | No token 401 | ☐ | ☐ | |
| TC-ACL-02 | Invalid token 401 | ☐ | ☐ | |
| TC-ACL-03 | Creator-only 403 | ☐ | ☐ | |
| TC-ACL-04 | Public results | ☐ | ☐ | |
| TC-ACL-05 | Page redirects | ☐ | ☐ | |
| TC-ACL-06 | Edit link owner-only | ☐ | ☐ | |
| TC-VAL-01..06 | Server validation table | ☐ | ☐ | |
| TC-CONC-01 | Vote storm PASS | ☐ | ☐ | |
| TC-CONC-02 | Counters = vote rows | ☐ | ☐ | |
| TC-CONC-03 | Parallel duplicate POST | ☐ | ☐ | |
| TC-CONC-04 | Concurrent edits, one wins | ☐ | ☐ | |
| TC-CONC-05 | Edit vs vote race | ☐ | ☐ | |
| TC-CONC-06 | Audit = vote rows | ☐ | ☐ | |
| TC-CONC-07 | FK safety net | ☐ | ☐ | |
| TC-AUD-01 | All action types | ☐ | ☐ | |
| TC-AUD-02 | Vote history | ☐ | ☐ | |
| TC-AUD-03 | Failures not audited | ☐ | ☐ | |
| TC-AUD-04 | Append-only | ☐ | ☐ | |

Tester: ____________  Date: ____________  Build/commit: ____________
