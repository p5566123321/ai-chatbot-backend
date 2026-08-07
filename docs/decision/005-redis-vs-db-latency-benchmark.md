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

# seed 200 conversations x 10 messages directly into Postgres — no LLM calls involved
./scripts/seed-benchmark-data.sh 200 10

# run the app from IntelliJ as usual (.env loaded), or:
./gradlew bootRun
```

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

## Known limitations of a local run

- Postgres and Redis are both on loopback via Docker on the same machine — this is the best case
  for the DB path and likely *understates* Redis's real-world advantage over a networked DB
  (e.g. RDS) or a DB under concurrent write load from other traffic.
- The seeded table has none of the tenant's other production data/indexes/bloat, so absolute DB
  numbers won't transfer directly — treat this as a measurement of the *relative* gap, not an
  absolute SLA number.

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
