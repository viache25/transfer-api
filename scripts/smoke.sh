#!/usr/bin/env bash
# Smoke test against a running Transfer API: health, authentication, accounts, a transfer,
# its idempotent replay and a key-reuse rejection. Stops with a non-zero exit code at the
# first mismatch.
#
#   BASE_URL=http://localhost:8080 API_KEY=dev-local-terminal-key scripts/smoke.sh
#
# BASE_URL defaults to http://localhost:8080; API_KEY defaults to the key the dev profile seeds,
# so it works as-is against `docker compose up`. Needs curl and jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-local-terminal-key}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"   # seconds to wait for the app to report UP

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "smoke: $tool is required" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body"
STATUS="-"
CTYPE="-"
checks=0

fail() {
  echo "FAIL: $*" >&2
  echo "  last response: HTTP $STATUS ($CTYPE)" >&2
  if [ -s "$BODY" ]; then sed 's/^/  /' "$BODY" >&2; echo >&2; fi
  exit 1
}

ok() {
  checks=$((checks + 1))
  echo "ok $checks - $*"
}

# request METHOD PATH [curl args...]: sets STATUS and CTYPE, writes the response body to $BODY.
request() {
  local method=$1 path=$2 out
  shift 2
  : > "$BODY"
  out="$(curl -sS -X "$method" -o "$BODY" -w '%{http_code} %{content_type}' "$@" "$BASE_URL$path")" \
    || fail "$method $path: no HTTP response"
  STATUS="${out%% *}"
  CTYPE="${out#* }"
}

expect_status() { [ "$STATUS" = "$1" ] || fail "$2: expected HTTP $1, got $STATUS"; }
expect_json() { jq -e "$1" "$BODY" >/dev/null || fail "$2: expected $1"; }
expect_problem_json() { [[ "$CTYPE" == application/problem+json* ]] || fail "$1: expected application/problem+json"; }

AUTH=(-H "X-API-Key: $API_KEY" -H "Content-Type: application/json")

echo "smoke test against $BASE_URL"

# 1. Health (polls while the app is still starting)
deadline=$((SECONDS + HEALTH_TIMEOUT))
until curl -fsS -o "$BODY" "$BASE_URL/actuator/health" 2>/dev/null && jq -e '.status == "UP"' "$BODY" >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || fail "GET /actuator/health: not UP after ${HEALTH_TIMEOUT}s"
  sleep 2
done
ok "GET /actuator/health is UP"

# 2. Authentication: no API key -> 401 RFC 7807
request POST /accounts -H "Content-Type: application/json" \
  -d '{"owner":"Mallory","initialBalance":1.00,"currency":"EUR"}'
expect_status 401 "POST /accounts without X-API-Key"
expect_problem_json "401 response"
ok "POST /accounts without X-API-Key -> 401 problem+json"

# 3. Two accounts
request POST /accounts "${AUTH[@]}" -d '{"owner":"Smoke Alice","initialBalance":100.00,"currency":"EUR"}'
expect_status 201 "create account Alice"
expect_json '.balance == 100 and .currency == "EUR"' "create account Alice"
ALICE="$(jq -r .id "$BODY")"
request POST /accounts "${AUTH[@]}" -d '{"owner":"Smoke Bob","initialBalance":0,"currency":"EUR"}'
expect_status 201 "create account Bob"
BOB="$(jq -r .id "$BODY")"
ok "POST /accounts twice -> 201 (accounts $ALICE and $BOB)"

# 4. Transfer with a fresh Idempotency-Key
KEY="smoke-$(date +%s)-$$-$RANDOM"
TRANSFER="$(printf '{"fromAccountId":%s,"toAccountId":%s,"amount":30.00}' "$ALICE" "$BOB")"
request POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" -d "$TRANSFER"
expect_status 201 "POST /transfers"
expect_json '.status == "COMPLETED" and .amount == 30' "POST /transfers"
TRANSFER_ID="$(jq -r .id "$BODY")"
ok "POST /transfers -> 201 COMPLETED (transfer $TRANSFER_ID)"

# 5. Replay: same request, same key -> 200 with the same transfer, money moved once
request POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" -d "$TRANSFER"
expect_status 200 "replay with the same Idempotency-Key"
expect_json ".id == $TRANSFER_ID" "replay with the same Idempotency-Key"
ok "same request with the same Idempotency-Key -> 200, same transfer $TRANSFER_ID"

request GET "/accounts/$ALICE" "${AUTH[@]}"
expect_status 200 "GET /accounts/$ALICE"
expect_json '.balance == 70' "Alice's balance after transfer + replay"
request GET "/accounts/$BOB" "${AUTH[@]}"
expect_status 200 "GET /accounts/$BOB"
expect_json '.balance == 30' "Bob's balance after transfer + replay"
ok "balances 70.00 / 30.00: the money moved exactly once"

# 6. The same key with a different payload is rejected, not replayed
request POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" \
  -d "$(printf '{"fromAccountId":%s,"toAccountId":%s,"amount":99.00}' "$ALICE" "$BOB")"
expect_status 422 "same Idempotency-Key with a different amount"
expect_problem_json "422 response"
ok "same Idempotency-Key with a different amount -> 422 problem+json"

echo "smoke test passed: $checks checks"
