# ADR-012: HikariCP Pool Sizing for Conversation Reads

**Status:** Accepted

## Context

While running the [ADR-005](005-redis-vs-db-latency-benchmark.md) Redis-vs-DB benchmark at
`VUS=50`, the "blended" scenario (all 200 seeded conversations, cache enabled) measured *worse*
tail latency than the pure-DB baseline — p95 climbed from the expected ~20ms range into the
70-120ms range across repeated runs, and one run produced a single request stuck for **3+
minutes** with a handful of non-200 responses. That result made no sense on its face: Redis was
enabled and the cache hit ratio was ~100%, so the read path should have been *faster* than the
DB-only baseline, not slower.

Ruled out before finding the real cause (in order tried):
- Stale Docker network reference on a recreated container — unrelated, fixed earlier, didn't
  affect this.
- Host CPU contention (IntelliJ background indexing at one point hit 265% CPU, host load average
  spiked to 20+ on a 10-core machine) — real, and worth controlling for in any local benchmark run,
  but a controlled rerun after the host settled reproduced the same bad numbers, so it wasn't the
  (sole) explanation.
- Thermal throttling (`pmset -g therm`) — no warning level recorded, ruled out.

**Root cause:** `ConversationController.getMessages` calls
`ConversationService.requireOwnedConversation` (ADR-007's ownership gate) *before* calling
`ConversationHistoryService.getHistory()`:

```kotlin
@GetMapping("/{conversationId}/messages")
fun getMessages(@PathVariable conversationId: String, @CurrentUserId ownerId: Long): List<MessageResponse> {
    conversationService.requireOwnedConversation(conversationId, ownerId)   // <- Postgres, every request
    return conversationHistoryService.getHistory(conversationId)            // <- Redis on a hit
        .map { MessageResponse(it.role, it.content, it.createdAt) }
}
```

`requireOwnedConversation` runs `conversationRepository.findByUuid(conversationId)` — a real
Postgres round trip — **on every request regardless of cache outcome**. `history.cache.time`
(ADR-005's whole measurement basis) only wraps the `getHistory()` call, so this cost is invisible
to the metric the benchmark was built around: a "100% cache hit" run still issues one Postgres
query per request.

Spring Boot's HikariCP auto-configuration defaults `maximum-pool-size` to 10 when nothing overrides
it (this project's `application.yaml` sets no `spring.datasource.hikari.*` at all). At `VUS=50`,
that's 50 concurrent requests each needing a connection for the ownership check, against a pool of
10. Live sampling during the bad run confirmed it directly:

```
hikaricp_connections_active=10   (pinned at max)
hikaricp_connections_pending=28-40   (threads queued waiting for a connection, sustained for the
                                       entire 2.5 minute run)
```

Raising the pool to 50 (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50`) made `pending` sit at 0
for the same traffic and dropped k6 p95 from 101-122ms to 21.5ms, with throughput going from
~1300 req/s to ~4970 req/s — no other change.

## Why this isn't just a benchmark artifact

The ownership check is unconditional on the real production read path, not something the
benchmark introduced. A pool sized for a lower-concurrency assumption will hit the same
`pending` queue under real concurrent traffic — cache hits do not exempt a request from needing a
Postgres connection. Whatever fraction of `GET /messages` p95 currently looks like "Redis
overhead" in production could actually be pool contention on this unconditional query, and it will
get worse as traffic grows even if the Redis hit ratio stays at 100%.

## Options considered

1. **Raise `hikari.maximum-pool-size` in `application.yaml`.** Simplest, but a pool size is a
   guess without matching it to actual Postgres `max_connections` and expected concurrent request
   volume in production — picking 50 here was reverse-engineered from one benchmark's VU count,
   not a capacity-planned number.
2. **Remove the extra round trip**: fold the ownership check into the same query `getHistory()`
   already needs (e.g. `DatabaseConversationHistoryService`'s query could filter on `owner_id`
   directly and return "not found" the same way), or cache `Conversation.ownerId` alongside the
   Redis message list so a cache hit never needs Postgres at all. This fixes the root cause instead
   of buying headroom, but touches the ADR-007 ownership contract (`requireOwnedConversation` is
   called from multiple write paths too — `ChatService`, `DocumentRepository` — not just this one
   read) and needs its own design pass.
3. **Do nothing for now, document it.** No production incident has been observed; this surfaced
   under synthetic 50-VU concurrent load against a 200-row seeded table, not measured production
   traffic.

## Decision

Go with option 1: `spring.datasource.hikari.maximum-pool-size` is now explicitly set to `50`
(`application.yaml`, overridable via `DB_HIKARI_MAX_POOL_SIZE`), rather than left at Spring Boot's
implicit default of 10. This is deliberately a stopgap, not a capacity-planned number — it's sized
to survive the concurrency this investigation happened to test (`VUS=50`), checked only against
Postgres's own default `max_connections=100` for headroom (a single app instance at pool=50 leaves
50 connections free for psql/admin/future pools). It removes the immediate risk of the pool being
the dominant bottleneck under moderate concurrent load; it does not address the root cause (option
2 — the unconditional per-request ownership query), which remains open.

Revisit this number, rather than treating 50 as final, once real production concurrency is known
or option 2 ships and changes how many Postgres round trips a conversation read needs at all.

## Future considerations

| Capability | Description |
|---|---|
| Capacity-planned pool sizing | Replace the stopgap `50` with a number derived from real expected concurrency and Postgres `max_connections`, once production traffic data exists. |
| Remove the per-request ownership round trip | Option 2 above — would also shave server p95 further on real cache hits, since they'd become Redis-only reads, and reduce how much pool headroom is even needed. |
| Reproduce at larger scale | Re-run the ADR-005 protocol with a bigger seeded dataset (see that doc's known-limitations note) and confirm pool sizing holds at higher `VUS`. |
