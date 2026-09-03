// Load test for GET /api/conversations/{id}/messages — the endpoint backed by
// ConversationHistoryService.getHistory(), i.e. the code path this whole benchmark exists to measure.
//
// It deliberately never calls the /messages POST (chat) endpoint: that path calls out to Gemini and
// its latency would drown out the Redis-vs-DB signal we actually care about.
//
// Run the SAME invocation twice — once with the app started with CONVERSATION_CACHE_ENABLED=true
// (default) and once with it set to false — to get a like-for-like comparison. See
// docs/decision/005-redis-vs-db-latency-benchmark.md for the full experiment protocol.
//
// Usage:
//   k6 run -e BASE_URL=http://localhost:$SERVER_PORT benchmark/k6-history-latency.js
//   k6 run -e BASE_URL=http://localhost:$SERVER_PORT -e VUS=50 -e DURATION=3m -e POOL_SIZE=50 \
//     benchmark/k6-history-latency.js
//
// Env vars:
//   BASE_URL           default http://localhost:8080 — this is only a fallback for the
//                      .env.example default; k6 does not read .env itself, so pass BASE_URL
//                      explicitly if SERVER_PORT differs (it does by default in this repo's own
//                      .env — see render-prometheus-config.sh)
//   VUS                default 20   (concurrent virtual users)
//   DURATION           default 2m   (steady-state duration, after ramp-up)
//   POOL_SIZE          default 0    (0 = use every seeded conversation id; set smaller, e.g. 50,
//                                    to concentrate traffic on a small "hot" set — mimics active
//                                    users and keeps the Redis cache warm within its 30 min TTL)
//   BENCH_USER_EMAIL      must match what seed-benchmark-data.sh registered (default
//   BENCH_USER_PASSWORD   bench@ai-chatbot.local / benchmark-password-123) — every seeded
//                         conversation is owned by this user (ADR-007), so setup() logs in as it
//                         once and every request carries its token.

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POOL_SIZE = parseInt(__ENV.POOL_SIZE || '0', 10);
const BENCH_USER_EMAIL = __ENV.BENCH_USER_EMAIL || 'bench@ai-chatbot.local';
const BENCH_USER_PASSWORD = __ENV.BENCH_USER_PASSWORD || 'benchmark-password-123';

const conversationIds = new SharedArray('conversation-ids', function () {
  const raw = open('./conversation-ids.txt');
  const ids = raw.split('\n').map((l) => l.trim()).filter(Boolean);
  if (ids.length === 0) {
    throw new Error(
      'benchmark/conversation-ids.txt is empty — run scripts/seed-benchmark-data.sh first'
    );
  }
  return POOL_SIZE > 0 ? ids.slice(0, POOL_SIZE) : ids;
});

export const options = {
  scenarios: {
    steady_state: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: parseInt(__ENV.VUS || '20', 10) }, // ramp-up, excluded from thresholds below
        { duration: __ENV.DURATION || '2m', target: parseInt(__ENV.VUS || '20', 10) }, // steady state — read this window in Grafana
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

// Runs once before VUs start, outside the timed scenario — logs in as the benchmark user so every
// VU iteration can reuse the same token rather than each one logging in for itself (that would
// mix auth.login.time into the very latency this benchmark exists to isolate).
export function setup() {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: BENCH_USER_EMAIL, password: BENCH_USER_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) {
    throw new Error(
      `login failed (status ${res.status}): ${res.body} — did you run scripts/seed-benchmark-data.sh first?`
    );
  }
  return { token: res.json('token') };
}

export default function (data) {
  const id = conversationIds[Math.floor(Math.random() * conversationIds.length)];
  const res = http.get(`${BASE_URL}/api/conversations/${id}/messages`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
