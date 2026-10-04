// k6 load test for the Transfer API.
//
// N virtual POS terminals, each with its own API key (so each has its own rate-limit bucket),
// send transfers between a shared pool of accounts. A share of the transfers is deliberately sent
// again with the same Idempotency-Key after the answer arrived (a terminal that lost the response),
// and a smaller share is sent twice at the same moment (a terminal that timed out and retried while
// the first request was still in flight). Every same-key answer must carry the original transfer id.
//
// setup() opens the accounts. teardown() reads every balance and the full transfer history back and
// reconciles them: the total is conserved, every account's balance equals its opening balance plus
// the transfers recorded for it, and no Idempotency-Key appears twice. A key executed twice would
// either show up as a second 201 (idempotency_violations) or as money the history cannot explain
// (ledger_violations); both thresholds require zero.
//
//   k6 run -e API_KEYS="$(load/provision-terminals.sh 10)" load/transfers.js
//
// All options (environment variables) are listed in load/README.md.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const API_KEYS = (__ENV.API_KEYS || 'dev-local-terminal-key').split(',').map((k) => k.trim()).filter((k) => k);
const TERMINALS = parseInt(__ENV.TERMINALS || String(API_KEYS.length), 10);
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = parseInt(__ENV.ACCOUNTS || '20', 10);
const RETRY_SHARE = parseFloat(__ENV.RETRY_SHARE || '0.2');
const RACE_SHARE = parseFloat(__ENV.RACE_SHARE || '0.05');
const PACING_SECONDS = parseFloat(__ENV.PACING || '0.1');
const P95_MS = parseInt(__ENV.P95_MS || '250', 10);

// A POS terminal gives up after this many sends of the same request.
const MAX_ATTEMPTS = 3;
// 1,000,000.00 per account, transfers of at most 20.00: no transfer fails for insufficient funds,
// so every non-2xx answer is a real error.
const INITIAL_BALANCE_CENTS = 100000000;
const PAGE_SIZE = 1000;

export const options = {
  scenarios: {
    terminals: {
      executor: 'constant-vus',
      vus: TERMINALS,
      duration: DURATION,
      exec: 'terminal',
    },
  },
  thresholds: {
    'http_req_failed{scenario:terminals}': ['rate<0.01'],
    'http_req_duration{scenario:terminals}': [`p(95)<${P95_MS}`],
    'http_reqs{scenario:terminals}': ['count>0'],
    idempotency_violations: ['count==0'],
    ledger_violations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '120s',
  teardownTimeout: '300s',
};

const transfersCreated = new Counter('transfers_created'); // answered 201
const transfersRecovered = new Counter('transfers_recovered'); // a failed send had committed; the same-key resend got 200
const replaysOk = new Counter('replays_ok'); // deliberate same-key retries answered 200 with the original id
const racesOk = new Counter('races_ok'); // concurrent same-key pairs: one execution, one id
const transientRetries = new Counter('transient_retries'); // resends after a lost response, 5xx, 409 conflict or 429
const idempotencyViolations = new Counter('idempotency_violations');
const ledgerViolations = new Counter('ledger_violations');
const ledgerTransfers = new Counter('ledger_transfers'); // transfers found in the history at the end

function cents(amount) {
  return Math.round(Number(amount) * 100);
}

function requestName(method, path) {
  return `${method} ${path.split('?')[0].replace(/\/\d+/g, '/{id}')}`;
}

// setup() and teardown() act as one more client using the first key. They wait out a 429
// instead of failing, so they never trip over the per-terminal rate limit.
function adminRequest(method, path, body) {
  for (let attempt = 1; attempt <= 20; attempt++) {
    const res = http.request(method, `${BASE_URL}${path}`, body || null, {
      headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEYS[0] },
      responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 429),
      tags: { name: requestName(method, path) },
    });
    if (res.status !== 429) {
      if (res.status < 200 || res.status > 299) {
        throw new Error(`${method} ${path}: HTTP ${res.status} ${res.body}`);
      }
      return res;
    }
    sleep(Number(res.headers['Retry-After'] || 1));
  }
  throw new Error(`${method} ${path}: still rate limited after 20 attempts`);
}

export function setup() {
  if (API_KEYS.length < TERMINALS) {
    console.warn(`${TERMINALS} terminals share ${API_KEYS.length} API key(s), so they share rate-limit buckets: expect 429s`);
  }
  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'GET /actuator/health' } });
  if (health.status !== 200) {
    throw new Error(`${BASE_URL}/actuator/health answered HTTP ${health.status}; is the stack up?`);
  }
  const runId = `k6-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const res = adminRequest('POST', '/accounts', JSON.stringify({
      owner: `${runId}-account-${i}`,
      initialBalance: INITIAL_BALANCE_CENTS / 100,
      currency: 'EUR',
    }));
    accounts.push(res.json('id'));
  }
  // Give both violation counters a sample, so their thresholds are always evaluated.
  idempotencyViolations.add(0);
  ledgerViolations.add(0);
  console.log(`run ${runId}: ${TERMINALS} terminals, ${API_KEYS.length} API keys, ${ACCOUNTS} accounts, ${DURATION}`);
  return { runId, accounts };
}

function isTransient(res) {
  if (res.status === 0 || res.status === 429 || res.status >= 500) {
    return true;
  }
  if (res.status === 409) {
    try {
      return res.json('title') === 'Concurrent Modification';
    } catch (e) {
      return false;
    }
  }
  return false;
}

function backoffSeconds(res, attempt) {
  if (res.status === 429) {
    return Number(res.headers['Retry-After'] || 1);
  }
  return 0.1 * attempt;
}

// The terminal's retry policy: after a lost response, a 5xx, a 409 Concurrent Modification or a
// 429 it sends the identical request again with the same Idempotency-Key. Returns the first
// non-transient answer (or the last transient one after MAX_ATTEMPTS) and the number of sends.
function sendWithRetries(url, body, params, firstResponse) {
  let res = firstResponse || http.post(url, body, params);
  let attempts = 1;
  while (isTransient(res) && attempts < MAX_ATTEMPTS) {
    transientRetries.add(1);
    sleep(backoffSeconds(res, attempts));
    res = http.post(url, body, params);
    attempts++;
  }
  return { res, attempts };
}

function idOf(res) {
  try {
    return res.json('id');
  } catch (e) {
    return undefined;
  }
}

function violation(key, message, res) {
  idempotencyViolations.add(1);
  console.error(`idempotency violation for ${key}: ${message} (HTTP ${res.status} ${res.body})`);
}

function giveUp(key, res) {
  console.warn(`${key}: gave up after ${MAX_ATTEMPTS} sends, last answer HTTP ${res.status} ${res.body}`);
}

function pickTwo(accounts) {
  const from = accounts[Math.floor(Math.random() * accounts.length)];
  let to = from;
  while (to === from) {
    to = accounts[Math.floor(Math.random() * accounts.length)];
  }
  return [from, to];
}

export function terminal(data) {
  const apiKey = API_KEYS[(exec.vu.idInTest - 1) % API_KEYS.length];
  const [from, to] = pickTwo(data.accounts);
  const amount = (1 + Math.floor(Math.random() * 2000)) / 100; // 0.01 .. 20.00
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount });
  const key = `${data.runId}-t${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;
  const url = `${BASE_URL}/transfers`;
  const params = (kind) => ({
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey, 'Idempotency-Key': key },
    tags: { name: 'POST /transfers', kind },
  });

  const roll = Math.random();
  if (roll < RACE_SHARE) {
    race(url, body, params('race'), key);
  } else {
    const first = transfer(url, body, params('transfer'), key);
    if (first && roll < RACE_SHARE + RETRY_SHARE) {
      replay(url, body, params('replay'), key, first);
    }
  }
  sleep(PACING_SECONDS);
}

// A fresh key: 201, or 200 only if an earlier send of this very request had already committed.
function transfer(url, body, params, key) {
  const { res, attempts } = sendWithRetries(url, body, params);
  if (res.status === 201) {
    transfersCreated.add(1);
    return res;
  }
  if (res.status === 200) {
    if (attempts === 1) {
      violation(key, 'a brand-new key was answered as a replay', res);
      return null;
    }
    transfersRecovered.add(1);
    return res;
  }
  giveUp(key, res);
  return null;
}

// The deliberate retry after the answer arrived: must be 200 with the original id, never a new 201.
function replay(url, body, params, key, first) {
  const { res } = sendWithRetries(url, body, params);
  if (isTransient(res)) {
    giveUp(key, res);
    return;
  }
  const ok = check(res, {
    'same-key retry is answered 200': (r) => r.status === 200,
    'same-key retry returns the original transfer id': (r) => idOf(r) === idOf(first),
  });
  if (ok) {
    replaysOk.add(1);
  } else {
    violation(key, `retry did not replay transfer ${idOf(first)}`, res);
  }
}

// The same request twice at the same moment: exactly one execution (at most one 201), and both
// answers carry the same transfer id.
function race(url, body, params, key) {
  const responses = http.batch([['POST', url, body, params], ['POST', url, body, params]])
    .map((r) => sendWithRetries(url, body, params, r).res);
  if (responses.some(isTransient)) {
    giveUp(key, responses.find(isTransient));
    return;
  }
  const created = responses.filter((r) => r.status === 201).length;
  const ok = check(responses, {
    'concurrent same-key pair: both answered 2xx': (rs) => rs.every((r) => r.status === 200 || r.status === 201),
    'concurrent same-key pair: at most one 201': () => created <= 1,
    'concurrent same-key pair: one transfer id': (rs) => idOf(rs[0]) !== undefined && idOf(rs[0]) === idOf(rs[1]),
  });
  if (ok) {
    racesOk.add(1);
    if (created === 1) {
      transfersCreated.add(1);
    } else {
      transfersRecovered.add(1);
    }
  } else {
    violation(key, `concurrent pair answered ${responses.map((r) => r.status).join('/')}`, responses[0]);
  }
}

export function teardown(data) {
  const finalCents = {};
  const deltaCents = {};
  for (const id of data.accounts) {
    finalCents[id] = cents(adminRequest('GET', `/accounts/${id}`).json('balance'));
    deltaCents[id] = 0;
  }

  // Every transfer of this run is between two of its accounts, so the union of their histories is
  // the whole run. Sorted by id, so paging is stable.
  const transfers = new Map();
  for (const id of data.accounts) {
    for (let page = 0; ; page++) {
      const content = adminRequest('GET', `/transfers?accountId=${id}&size=${PAGE_SIZE}&page=${page}&sort=id,asc`)
        .json('content');
      content.forEach((t) => transfers.set(t.id, t));
      if (content.length < PAGE_SIZE) {
        break;
      }
    }
  }

  const keys = new Set();
  let duplicateKeys = 0;
  let foreignKeys = 0;
  for (const t of transfers.values()) {
    if (keys.has(t.idempotencyKey)) {
      duplicateKeys++;
    }
    keys.add(t.idempotencyKey);
    if (!t.idempotencyKey.startsWith(`${data.runId}-`)) {
      foreignKeys++;
    }
    deltaCents[t.fromAccountId] -= cents(t.amount);
    deltaCents[t.toAccountId] += cents(t.amount);
  }

  const expectedTotal = data.accounts.length * INITIAL_BALANCE_CENTS;
  const actualTotal = data.accounts.reduce((sum, id) => sum + finalCents[id], 0);
  const unexplained = data.accounts.filter((id) => finalCents[id] !== INITIAL_BALANCE_CENTS + deltaCents[id]);

  check(null, {
    'ledger: total balance is conserved': () => actualTotal === expectedTotal,
    'ledger: every balance equals opening balance + recorded transfers': () => unexplained.length === 0,
    'ledger: no Idempotency-Key was executed twice': () => duplicateKeys === 0,
    'ledger: every transfer belongs to this run': () => foreignKeys === 0,
  });
  const violations = (actualTotal === expectedTotal ? 0 : 1) + unexplained.length + duplicateKeys + foreignKeys;
  ledgerViolations.add(violations);
  ledgerTransfers.add(transfers.size);

  console.log(`ledger: ${transfers.size} transfers, total ${(actualTotal / 100).toFixed(2)} EUR `
    + `(expected ${(expectedTotal / 100).toFixed(2)}), ${unexplained.length} accounts unexplained, `
    + `${duplicateKeys} duplicate keys, ${foreignKeys} foreign keys`);
  if (violations > 0) {
    throw new Error(`ledger reconciliation failed with ${violations} violation(s); accounts off: ${unexplained.join(', ')}`);
  }
}
