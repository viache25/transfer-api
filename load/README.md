# Load test (k6)

`load/transfers.js` puts the API under concurrent load the way a fleet of POS terminals would,
and then checks that the books still balance.

- **N virtual terminals**, each with its own API key, so each has its own rate-limit bucket
  (20 requests/s per terminal by default). Every terminal sends a transfer between two random
  accounts of a shared pool, then pauses `PACING` seconds, which keeps it below its limit.
- **Deliberate same-key retries.** 20% of the transfers are sent a second time with the same
  `Idempotency-Key` after the answer arrived (a terminal that lost the response). The answer must
  be `200` with the original transfer id. Another 5% are sent twice at the same moment (a terminal
  that timed out and retried while the first request was still in flight). That pair must produce
  at most one `201`, and both answers must carry the same transfer id.
- **A terminal's retry policy.** A lost response, a `5xx`, a `409 Concurrent Modification` or a
  `429` is retried with the same key, up to 3 sends. These retries count as errors in the error
  rate, but they show whether idempotency holds when retries happen for real reasons.
- **Ledger reconciliation after the run** (`teardown()`). Every balance and the full transfer
  history of the run's accounts are read back. The run fails unless:
  - the total balance is conserved,
  - every account's balance equals its opening balance plus the transfers recorded for it, and
  - no `Idempotency-Key` appears twice.

  Conservation alone would not catch a double execution, because a transfer applied twice still
  moves money from one account to another without creating any. The per-account check catches
  money that moved without a matching record. A key that executed twice shows up as a second
  `201` for the same key, which fails the `idempotency_violations` threshold.

## Thresholds

| Metric | Threshold |
|---|---|
| `http_req_failed{scenario:terminals}` | rate < 1% |
| `http_req_duration{scenario:terminals}` | p95 < `P95_MS` (default 250 ms) |
| `idempotency_violations` | 0: no same-key answer with a different id, no second `201` |
| `ledger_violations` | 0: the reconciliation above |

k6 exits non-zero when a threshold fails (and `teardown()` throws when the ledger doesn't
reconcile), so the run can gate a pipeline.

## Running it against `docker compose up`

Needs [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (or Docker, see below).

```bash
docker compose up -d --build                       # the full stack, dev profile
API_KEYS="$(load/provision-terminals.sh 10)"       # 10 terminals, keys printed comma-separated
k6 run -e API_KEYS="$API_KEYS" load/transfers.js
```

`load/provision-terminals.sh N` creates N terminals in the compose PostgreSQL the same way
README "Deploying to a server" and `scripts/smoke-ephemeral.sh` do: a random key per terminal,
only its SHA-256 hash in the `terminals` table, and a unique name per terminal (the rate limiter
keys its buckets by terminal name). Without `API_KEYS` the script falls back to the dev key, but
then all terminals share one bucket and hit `429`s.

Without a local k6 install, run it from the k6 image (on macOS/Windows, use
`BASE_URL=http://host.docker.internal:8080` instead of `--network host`):

```bash
docker run --rm -i --network host -e API_KEYS="$API_KEYS" -v "$PWD/load:/load" \
  grafana/k6:2.3.0 run /load/transfers.js
```

Each run opens its own accounts, so runs don't interfere with each other or with data that is
already there.

### Options

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | Where the API runs |
| `API_KEYS` | the dev key | Comma-separated API keys, one per terminal |
| `TERMINALS` | number of keys | Virtual terminals (k6 VUs) |
| `DURATION` | `60s` | How long the terminals send transfers |
| `ACCOUNTS` | `20` | Size of the shared account pool (fewer accounts mean more lock contention) |
| `RETRY_SHARE` | `0.2` | Share of transfers retried with the same key after the answer |
| `RACE_SHARE` | `0.05` | Share of transfers sent twice concurrently with the same key |
| `PACING` | `0.1` | Seconds each terminal waits between transfers |
| `P95_MS` | `250` | p95 latency threshold in milliseconds |

## In CI

[`.github/workflows/load.yml`](../.github/workflows/load.yml) starts the compose stack
(`postgres`, `redis`, `app`) on a GitHub runner, provisions 10 terminals, runs the script for 60s
and writes the numbers to the run summary. The k6 summary (`summary.json`) and an HTML report
(k6 web dashboard export) are uploaded as the `k6-report` artifact.

It runs weekly (Sundays 04:00 UTC), on demand (`workflow_dispatch`, with the number of terminals
and the duration as inputs), and on pull requests that change `load/` or the workflow. It is not
part of the per-PR CI gate: it builds the image and runs for a minute, and latency measured on a
shared runner is too noisy to block unrelated changes on.

## Results

Measured on a GitHub-hosted `ubuntu-latest` runner (PR #32, run 37236768387): 10 terminals,
20 accounts, 60s, with the app, PostgreSQL and Redis on the same machine as k6.

| Metric | Value |
|---|---|
| Requests (terminals scenario) | 6,746 (109 req/s) |
| p95 / p99 latency | 11.2 ms / 27.6 ms |
| Error rate | 0.25% (17 requests, see below) |
| Transfers in the ledger | 5,384 (= transfers answered `201`) |
| Same-key retries answered with the original id | 1,075 sequential, 270 concurrent pairs |
| Idempotency / ledger violations | 0 / 0 |

The 17 failed requests were 15 `409 Concurrent Modification` answers, where all 3 optimistic-lock
attempts lost and the terminal's retry with the same key then succeeded, plus 2 requests that
found a real bug. Two opposite transfers between the same pair of accounts (A→B and B→A)
committing at the same moment hit a PostgreSQL **deadlock** (`40P01`). Hibernate flushes the two
account updates in load order, so the two transactions lock the rows in opposite order. The
deadlock exception was not handled, and the resulting error dispatch was rejected by the security
filter chain, so the terminal got a misleading `401 Missing or invalid X-API-Key header` instead
of a `5xx` or a retryable `409`. No money was affected: the deadlock victim rolled back, and the
ledger reconciled. The fix is tracked as a separate follow-up PR.
