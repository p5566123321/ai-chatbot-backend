# ADR-005: Redis vs. Pure-DB Latency Benchmark

**Status:** Accepted

## Context

`ConversationHistoryService.getHistory()` is a cache-aside read: Redis first, Postgres on a miss
(see [ADR-003](003-memory-strategy.md)). That design decision was made on reasoning, not
measurement. This doc is the protocol for putting a number on what Redis is actually buying us —
both the best case (warm cache) and the cost of *not* having it — so the trade-off can be
revisited with data instead of assumption.

## Decision

Benchmark the existing `getHistory()` code path under load, twice, with only one variable
changed: whether Redis is consulted at all. Reuse the metrics the service already emits rather
than building a separate harness, and drive traffic with k6 against seeded data so the LLM call
never enters the measurement.

## What's already in place

- `history.cache.time` — Micrometer `Timer`, tagged `result=hit|miss|disabled`, p50/p95/p99.
- `history.db.fallback.time` — `Timer` for the DB query on a miss, tagged `cacheEnabled=true|false`.
- `app.conversation.cache.enabled` (env `CONVERSATION_CACHE_ENABLED`, default `true`) — set to
  `false` to force every request through Postgres, with zero Redis calls. This is what makes the
  "pure DB" baseline a like-for-like comparison against the same code path, same traffic shape,
  instead of a different branch/deployment.
- `/actuator/prometheus` — already exposed (`management.endpoints.web.exposure.include: health,prometheus`).

## One-time setup

```bash
# render prometheus.yml for the current SERVER_PORT (from .env; falls back to 8080)
source scripts/load-env.sh && ./scripts/render-prometheus-config.sh

# infra: Postgres + Redis (as usual) plus Prometheus + Grafana for this benchmark
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d

# run the app from IntelliJ as usual (.env loaded), or:
./gradlew bootRun

# seed 200 conversations x 10 messages directly into Postgres — no LLM calls involved. Must run
# AFTER the app is up: it registers a benchmark user (bench@ai-chatbot.local) via the app's own
# /api/auth/register and stamps every seeded conversation's owner_id to that user's id, because
# ADR-007's ownership check 404s any conversation whose owner_id doesn't match the caller — a null
# owner_id (pre-ADR-007 seed rows) never matches, so this isn't optional plumbing.
./scripts/seed-benchmark-data.sh 200 10
```

`hikari.maximum-pool-size` matters even for the "Redis" scenarios, not just the DB one — see the
callout below the protocol table and [ADR-012](012-hikaricp-pool-sizing-for-conversation-reads.md),
which is why it's now `50` by default (`application.yaml`, overridable via
`DB_HIKARI_MAX_POOL_SIZE`) rather than something this protocol has to set manually.
The k6 script logs in as the benchmark user in its `setup()` and reuses that token for every
request; `BENCH_USER_EMAIL`/`BENCH_USER_PASSWORD` env vars let you point both scripts at a
different account if needed (must match between seed and k6 runs).

Grafana: http://localhost:3000 → dashboard **Benchmark / Redis vs DB — Conversation History Latency**
(pre-provisioned, nothing to configure). Prometheus: http://localhost:9090.

Install [k6](https://k6.io/docs/get-started/installation/) locally to run the load test. Its
`BASE_URL` default is a fallback only — pass it explicitly to match your actual port, e.g.
`k6 run -e BASE_URL=http://localhost:$SERVER_PORT ...`.

`observability/prometheus/prometheus.yml` is generated (gitignored) — re-run
`render-prometheus-config.sh` whenever `SERVER_PORT` changes. Prometheus's own config format has
no env-var substitution, and Docker Compose only substitutes variables inside the compose file
itself, not inside files it mounts, hence the separate render step.

## Experiment protocol

Run each scenario for a few minutes; only read the *steady-state* window in Grafana (the k6 script
ramps up for 20s and down for 10s — ignore those edges, they're startup noise, not the effect being
measured). Restart the app between scenarios A/C and B since `CONVERSATION_CACHE_ENABLED` is
read at startup.

`$PORT` below is `$SERVER_PORT` from your `.env` (source `scripts/load-env.sh` first) — k6 doesn't
read `.env` itself, so `BASE_URL` must be passed explicitly.

| Scenario | App config | k6 invocation | What it measures |
|---|---|---|---|
| A. Redis, warm | default (`CONVERSATION_CACHE_ENABLED=true`) | `k6 run -e BASE_URL=http://localhost:$PORT -e POOL_SIZE=50 -e VUS=50 -e DURATION=2m benchmark/k6-history-latency.js` | Best case: small hot set of conversations, cache stays warm the whole run. |
| B. Pure DB | `CONVERSATION_CACHE_ENABLED=false` | `k6 run -e BASE_URL=http://localhost:$PORT -e VUS=50 -e DURATION=2m benchmark/k6-history-latency.js` | Baseline with Redis fully out of the picture. |
| C. Blended | default | `k6 run -e BASE_URL=http://localhost:$PORT -e VUS=50 -e DURATION=2m benchmark/k6-history-latency.js` (no `POOL_SIZE` → all 200 ids) | Realistic hit ratio when traffic spreads across more conversations than fit "hot". |

For each scenario, from the Grafana dashboard record:

| Metric | A. warm | B. pure DB | C. blended |
|---|---|---|---|
| p50 (redis hit / effective) | | — | |
| p95 | | | |
| p99 | | | |
| p50/p95/p99, DB path only (`history.db.fallback.time`) | — | | |
| cache hit ratio | | 0% | |
| HikariCP active connections (peak) | | | |
| k6 `http_req_duration` p95 (end-to-end, includes network+JSON) | | | |
| k6 throughput (req/s) | | | |

`history.cache.time` and `history.db.fallback.time` are pre-computed client-side quantiles
(`publishPercentiles`), so query them directly in Grafana/PromQL — no `histogram_quantile()` needed,
e.g. `history_cache_time_seconds{result="hit", quantile="0.95"}`. If this ever needs to be
aggregated across multiple instances, switch to `publishPercentileHistogram()` in
`ConversationHistoryService` first; single-instance local benchmarking doesn't need that.

**Important:** `GET /messages` calls `ConversationService.requireOwnedConversation` (ADR-007)
before `getHistory()` on every request — a Postgres query that runs on a cache *hit* too, and is
not captured by `history.cache.time`. At `VUS=50` this saturates Spring Boot's default
`hikari.maximum-pool-size=10`, which dominates tail latency independently of Redis. All runs below
use `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50` for this reason — see
[ADR-012](012-hikaricp-pool-sizing-for-conversation-reads.md) for the full story. Keep pool size
identical across scenarios in any future re-run of this protocol; it is a shared confound, not a
per-scenario variable.

## Results (2026-09-02, local run)

Seeded with `./scripts/seed-benchmark-data.sh 200 10` (200 conversations x 10 messages), `VUS=50`,
`DURATION=2m`, `hikari.maximum-pool-size=50` for every scenario (this predates ADR-012 making 50
the app-wide default — at the time these numbers were measured it was set explicitly per run).

| Metric | A. warm (`POOL_SIZE=50`) | B. pure DB | C. blended (all 200 ids) |
|---|---|---|---|
| Server p50 (`history.cache.time`\* / `history.db.fallback.time`) | 3.79ms | 4.16ms | 3.53ms |
| **Server p95** | **16.77ms** | **32.47ms** | **16.24ms** |
| Server p99 | 35.64ms | 129.99ms | 31.45ms |
| cache hit ratio | ~100% | 0% (disabled) | ~100% |
| k6 `http_req_duration` p50 | 7.74ms | 8.23ms | 7.33ms |
| **k6 `http_req_duration` p95** | **24.34ms** | **63.42ms** | **21.50ms** |
| k6 throughput | 4561 req/s | 2541 req/s | 4973 req/s |
| `http_req_failed` | 0% | 0% | 0% |

\* `result="hit"` series; miss ratio was ~0% in both A and C once warm.

**Redis reduces p95 latency by 48–50% server-side, 62–66% end-to-end**, and roughly doubles
throughput at this concurrency. C essentially matches A rather than sitting between A and B as
originally hypothesized — see the limitation below on why the "blended" scenario didn't exercise
the dilution effect it was designed to measure.

## Known limitations of a local run

- Postgres and Redis are both on loopback via Docker on the same machine — this is the best case
  for the DB path and likely *understates* Redis's real-world advantage over a networked DB
  (e.g. RDS) or a DB under concurrent write load from other traffic.
- The seeded table has none of the tenant's other production data/indexes/bloat, so absolute DB
  numbers won't transfer directly — treat this as a measurement of the *relative* gap, not an
  absolute SLA number.
- The host running both the app and the load generator is not otherwise idle (IDE, browser,
  other containers) — CPU contention from unrelated processes measurably inflated tail latency
  during this run's earlier attempts (p95 swung from ~25ms to 100ms+ run to run with no app-level
  change). Check `uptime`/`ps` for CPU hogs before trusting a single run's tail percentiles; prefer
  a rerun that reproduces over a one-off number.
- **200 conversations is too small a working set to exercise scenario C's intended "diluted hit
  ratio" effect** — it fits entirely in Redis well within the 30 min TTL, so C measured ~100% hit
  ratio, same as A. To actually observe the cache-dilution case the protocol was designed for,
  reseed with an order of magnitude more conversations (e.g. 2000+) so the working set exceeds what
  stays realistically "hot".

## Cleanup

```bash
./scripts/cleanup-benchmark-data.sh
docker compose -f docker-compose.yml -f docker-compose.observability.yml down
```

## Future considerations

| Capability | Description |
|---|---|
| Cross-instance percentiles | Switch the two Timers to `publishPercentileHistogram()` if this ever needs to run against more than one app instance. |
| Networked baseline | Re-run against a real networked DB (not loopback) to get an absolute number for the production trade-off, not just the relative gap. |
