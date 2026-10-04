#!/usr/bin/env bash
# Provisions N terminals (default 10) in the database of a running `docker compose up` stack and
# prints their API keys, comma-separated, ready for k6's API_KEYS:
#
#   API_KEYS="$(load/provision-terminals.sh 10)" k6 run load/transfers.js
#
# Same procedure as README "Deploying to a server" and scripts/smoke-ephemeral.sh: a random key per
# terminal, only its SHA-256 hash goes into the terminals table. Every terminal gets a unique name,
# because the rate limiter keeps one bucket per terminal name. Extra `docker compose` arguments
# (for example `-f other.yml -p project`) can be passed in COMPOSE_ARGS. Needs docker compose and
# openssl.
set -euo pipefail

COUNT="${1:-10}"
[[ "$COUNT" =~ ^[1-9][0-9]*$ ]] || { echo "usage: $0 [number-of-terminals]" >&2; exit 2; }
cd "$(dirname "$0")/.."

# shellcheck disable=SC2206 # word splitting of COMPOSE_ARGS is intended
COMPOSE=(docker compose ${COMPOSE_ARGS:-})

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}

batch="$(date +%s)-$RANDOM"
keys=()
values=()
for i in $(seq 1 "$COUNT"); do
  key="$(openssl rand -hex 24)"
  keys+=("$key")
  values+=("('load-$batch-$i', '$(printf %s "$key" | sha256)', now())")
done

sql="INSERT INTO terminals(name, api_key_hash, created_at) VALUES $(IFS=,; echo "${values[*]}");"
"${COMPOSE[@]}" exec -T postgres psql -U transferapi -d transferapi -v ON_ERROR_STOP=1 -q -c "$sql" >&2
echo "provisioned $COUNT terminals (load-$batch-1 .. load-$batch-$COUNT)" >&2

(IFS=,; echo "${keys[*]}")
