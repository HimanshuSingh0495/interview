#!/usr/bin/env bash
# Vote storm: hammers one poll with parallel votes to show that no vote is lost or duplicated (PLAN.md NF2).
#
#   ./scripts/vote-storm.sh [BASE_URL] [USERS]
#   ./scripts/vote-storm.sh                              # http://localhost:8081, 30 voters
#   ./scripts/vote-storm.sh http://localhost:9000 100
#   PARALLEL=20 PUTS_PER_USER=5 ./scripts/vote-storm.sh  # optional tuning
#
# Steps:
#   1. register an owner and create a 3-option poll
#   2. register USERS voters (unique names: timestamp + random suffix)
#   3. POST a vote for every voter, twice each, all in parallel   -> expect USERS x 201 and USERS x 409
#   4. PUT PUTS_PER_USER changes per voter to random options, in parallel -> expect all 200
#   5. GET /api/v1/polls/share/{shareId} and compare totalVotes with USERS
# PASS when totalVotes == USERS, exactly USERS POSTs returned 201, and no request returned 5xx. Otherwise FAIL (exit 1).
#
# Needs only bash, curl, xargs and sed/grep (no jq). Works on macOS and Linux.
set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
BASE_URL="${BASE_URL%/}"
USERS="${2:-30}"
PARALLEL="${PARALLEL:-50}"
PUTS_PER_USER="${PUTS_PER_USER:-3}"
PASSWORD="storm-pass-123"
API="$BASE_URL/api/v1"

case "$USERS" in ''|*[!0-9]*) echo "USERS must be a positive number, got '$USERS'" >&2; exit 2 ;; esac
[ "$USERS" -ge 1 ] || { echo "USERS must be at least 1" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/vote-storm.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Unique run id: [A-Za-z0-9_] only, short enough that "<run>_v<n>" stays within the 30-char username limit.
RUN="s$(date +%s)_$(( RANDOM % 9000 + 1000 ))"

# json_field <name> : first value of "name":... in the JSON on stdin (string or number)
json_field() {
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}[,}].*/\1/p" | head -1
}

# post_json <url> <json> [token] : prints the body, then the status code on its own last line
post_json() {
  local auth=()
  [ -n "${3:-}" ] && auth=(-H "Authorization: Bearer $3")
  curl -sS -X POST "$1" -H 'Content-Type: application/json' ${auth[@]+"${auth[@]}"} -d "$2" -w '\n%{http_code}'
}

register() { # register <username> -> token on stdout
  local out code
  out="$(post_json "$API/auth/register" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}")"
  code="$(printf '%s\n' "$out" | tail -1)"
  if [ "$code" != "201" ]; then
    echo "register $1 failed: HTTP $code $(printf '%s\n' "$out" | sed '$d')" >&2
    return 1
  fi
  printf '%s\n' "$out" | sed '$d' | json_field token
}

echo "== Vote storm against $BASE_URL with $USERS voters (run id $RUN)"
if ! curl -s -o /dev/null --max-time 5 "$BASE_URL/login"; then
  echo "Cannot reach $BASE_URL. Is the app running?" >&2
  exit 2
fi

# ---- 1. owner + poll ----------------------------------------------------------
OWNER_TOKEN="$(register "${RUN}_owner")"
POLL_OUT="$(post_json "$API/polls" \
  "{\"question\":\"Vote storm $RUN: pick a colour\",\"options\":[\"Red\",\"Green\",\"Blue\"]}" "$OWNER_TOKEN")"
POLL_CODE="$(printf '%s\n' "$POLL_OUT" | tail -1)"
POLL_JSON="$(printf '%s\n' "$POLL_OUT" | sed '$d')"
[ "$POLL_CODE" = "201" ] || { echo "create poll failed: HTTP $POLL_CODE $POLL_JSON" >&2; exit 1; }
POLL_ID="$(printf '%s' "$POLL_JSON" | json_field id)"
SHARE_ID="$(printf '%s' "$POLL_JSON" | json_field shareId)"
OPTION_IDS=()
while IFS= read -r line; do OPTION_IDS+=("$line"); done \
  < <(printf '%s' "$POLL_JSON" | grep -o '"optionId":[0-9]*' | sed 's/.*://')
[ "${#OPTION_IDS[@]}" -eq 3 ] || { echo "expected 3 options, got: $POLL_JSON" >&2; exit 1; }
echo "1. Created poll id=$POLL_ID shareId=$SHARE_ID options=${OPTION_IDS[*]}"

# ---- 2. voters (registered in parallel) -----------------------------------------
TOKENS="$WORK/tokens"
mkdir "$WORK/reg"
# One output file per request: parallel writers sharing one stdout can interleave their lines.
seq 1 "$USERS" | xargs -P "$PARALLEL" -I{} \
  curl -sS -X POST "$API/auth/register" -H 'Content-Type: application/json' \
       -d "{\"username\":\"${RUN}_v{}\",\"password\":\"$PASSWORD\"}" -o "$WORK/reg/{}.json" || true
: > "$TOKENS"
for f in "$WORK/reg/"*.json; do
  tok="$(json_field token < "$f")"
  if [ -n "$tok" ]; then echo "$tok" >> "$TOKENS"; fi
done
REGISTERED="$(wc -l < "$TOKENS" | tr -d ' ')"
[ "$REGISTERED" -eq "$USERS" ] || { echo "only $REGISTERED of $USERS voters registered:"; grep -hv '"token"' "$WORK/reg/"*.json | head -5; exit 1; } >&2
echo "2. Registered $REGISTERED voters"

random_option() { echo "${OPTION_IDS[$(( RANDOM % 3 ))]}"; }

# fire <METHOD> <jobs file> <out file> : each job line is "<token> <optionId>"; writes one status code per request
fire() {
  # shellcheck disable=SC2016  # $0/$1 expand inside the child shell, not here
  xargs -P "$PARALLEL" -n 2 sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X '"$1"' \
      "'"$API/polls/$POLL_ID/votes"'" -H "Content-Type: application/json" \
      -H "Authorization: Bearer $0" -d "{\"optionId\":$1}"' < "$2" > "$3" || true
}

# count <file> <code> : number of lines equal to code
count() { grep -c "^$2\$" "$1" || true; }

# ---- 3. duplicate POSTs ------------------------------------------------------------
: > "$WORK/post.jobs"
while IFS= read -r t; do
  echo "$t $(random_option)" >> "$WORK/post.jobs"
  echo "$t $(random_option)" >> "$WORK/post.jobs"
done < "$TOKENS"
# Interleave so a voter's two POSTs are not always neighbours (sort -R is not on every system; awk is).
awk 'BEGIN{srand()} {print rand() "\t" $0}' "$WORK/post.jobs" | sort -n | cut -f2- > "$WORK/post.shuffled"
fire POST "$WORK/post.shuffled" "$WORK/post.codes"
P201="$(count "$WORK/post.codes" 201)"; P409="$(count "$WORK/post.codes" 409)"
POTHER="$(( $(wc -l < "$WORK/post.codes") - P201 - P409 ))"
echo "3. POST x2 per voter ($(( USERS * 2 )) requests): 201=$P201 409=$P409 other=$POTHER   (expect 201=$USERS 409=$USERS)"

# ---- 4. parallel vote changes -----------------------------------------------------
: > "$WORK/put.jobs"
while IFS= read -r t; do
  for _ in $(seq 1 "$PUTS_PER_USER"); do echo "$t $(random_option)" >> "$WORK/put.jobs"; done
done < "$TOKENS"
awk 'BEGIN{srand()} {print rand() "\t" $0}' "$WORK/put.jobs" | sort -n | cut -f2- > "$WORK/put.shuffled"
fire PUT "$WORK/put.shuffled" "$WORK/put.codes"
U200="$(count "$WORK/put.codes" 200)"
UOTHER="$(( $(wc -l < "$WORK/put.codes") - U200 ))"
echo "4. PUT x$PUTS_PER_USER per voter ($(( USERS * PUTS_PER_USER )) requests): 200=$U200 other=$UOTHER   (expect 200=$(( USERS * PUTS_PER_USER )))"

# ---- 5. results --------------------------------------------------------------------
RESULT="$(curl -sS "$API/polls/share/$SHARE_ID")"
TOTAL="$(printf '%s' "$RESULT" | json_field totalVotes)"
echo "5. Results from GET /api/v1/polls/share/$SHARE_ID:"
printf '%s' "$RESULT" | grep -o '"optionId":[0-9]*,"text":"[^"]*","voteCount":[0-9]*' |
  sed 's/"optionId":\([0-9]*\),"text":"\([^"]*\)","voteCount":\([0-9]*\)/   option \1 (\2): \3/'
echo "   totalVotes: $TOTAL   (expect $USERS)"

ALL="$WORK/all.codes"; cat "$WORK/post.codes" "$WORK/put.codes" > "$ALL"
A201="$(count "$ALL" 201)"; A200="$(count "$ALL" 200)"; A409="$(count "$ALL" 409)"
A5XX="$(grep -c '^5' "$ALL" || true)"
AOTHER="$(( $(wc -l < "$ALL") - A201 - A200 - A409 ))"
echo
echo "Status codes over all vote requests: 201=$A201 200=$A200 409=$A409 other=$AOTHER (5xx=$A5XX)"
if [ "$AOTHER" -gt 0 ]; then
  echo "Other codes seen: $(grep -v -e '^201$' -e '^200$' -e '^409$' "$ALL" | sort | uniq -c | tr '\n' ' ')"
fi

echo
echo "Poll page (open it to see the pie chart): $BASE_URL/p/$SHARE_ID"
echo "shareId: $SHARE_ID   pollId: $POLL_ID"
echo
echo "Next, check the stored counters match the vote rows (must return 0 rows):"
echo "  ./scripts/db.sh \"SELECT o.id, o.vote_count, COUNT(v.id) FROM poll_option o LEFT JOIN vote v ON v.option_id=o.id GROUP BY o.id, o.vote_count HAVING o.vote_count <> COUNT(v.id)\""
echo "And the audit trail for this poll (expect $USERS VOTE_CAST, up to $(( USERS * PUTS_PER_USER )) VOTE_CHANGED; a PUT to the option you already have is not audited):"
echo "  ./scripts/db.sh \"SELECT action, COUNT(*) FROM audit_event WHERE poll_id=$POLL_ID GROUP BY action\""
echo

if [ "$TOTAL" = "$USERS" ] && [ "$P201" = "$USERS" ] && [ "$A5XX" -eq 0 ]; then
  echo "PASS: totalVotes=$TOTAL == USERS=$USERS, $P201 votes created, no 5xx"
  exit 0
fi
echo "FAIL: totalVotes=$TOTAL (expect $USERS), POST 201=$P201 (expect $USERS), 5xx=$A5XX (expect 0)"
exit 1
