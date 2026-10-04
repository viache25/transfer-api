#!/usr/bin/env bash
# Ephemeral-environment smoke test (D10): starts the given image with PostgreSQL and Redis
# (docker-compose.smoke.yml), provisions a terminal API key the way "Deploying to a server" in
# the README describes, runs scripts/smoke.sh against it and always tears the environment down.
#
#   scripts/smoke-ephemeral.sh ghcr.io/viache25/transfer-api:latest
#
# SMOKE_PORT (default 8080) picks the host port. Needs docker compose, curl, jq and openssl.
set -euo pipefail

export IMAGE="${1:?usage: $0 <image>}"
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.smoke.yml -p transfer-api-smoke)
BASE_URL="http://localhost:${SMOKE_PORT:-8080}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- app logs ---"
    "${COMPOSE[@]}" logs --no-color app || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

echo "starting $IMAGE with PostgreSQL and Redis"
"${COMPOSE[@]}" up -d --quiet-pull

# The schema (including the terminals table) exists once the app is up: Flyway runs at startup.
echo "waiting up to ${STARTUP_TIMEOUT}s for $BASE_URL/actuator/health"
deadline=$((SECONDS + STARTUP_TIMEOUT))
until curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "app did not become healthy within ${STARTUP_TIMEOUT}s" >&2
    exit 1
  fi
  sleep 2
done

# Production profile: no dev key is seeded, so provision a terminal like an operator would.
KEY="$(openssl rand -hex 24)"
if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::$KEY"; fi
if command -v sha256sum >/dev/null; then
  HASH="$(printf %s "$KEY" | sha256sum | cut -d' ' -f1)"
else
  HASH="$(printf %s "$KEY" | shasum -a 256 | cut -d' ' -f1)"
fi
"${COMPOSE[@]}" exec -T postgres psql -U transferapi -d transferapi -v ON_ERROR_STOP=1 -q \
  -c "INSERT INTO terminals(name, api_key_hash, created_at) VALUES ('smoke-test', '$HASH', now());"
echo "provisioned terminal 'smoke-test'"

BASE_URL="$BASE_URL" API_KEY="$KEY" scripts/smoke.sh
