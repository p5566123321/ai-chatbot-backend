# ADR-006: Queue Technology Selection (Phase 3)

**Status:** Accepted

## Context

`docs/architecture.md` Phase 3 ("Async Queue") calls for decoupling heavy processing, preventing
request blocking, and adding a retry mechanism. The MVP technology listed there is BullMQ — but
BullMQ is a Node.js-only library with no JVM client, and this is a Kotlin/Spring Boot backend, so
that choice doesn't actually fit the stack it was written for. This ADR replaces it.

The one genuinely long-running operation in the app today is the Gemini call
(`ChatService.chat`/`streamChat`) — there's no embeddings/RAG work yet (that's Phase 4). This
round builds the queue abstraction and a working Redis Streams adapter; migrating
`streamChat`'s existing `CompletableFuture.runAsync` dispatch onto it is deliberately deferred to
a follow-up (see "Future considerations") rather than bundled in here, since that path was just
hardened for resilient SSE reconnect/resume and deserves its own focused change.

## Decision

**Redis Streams, behind a `JobQueue<T>`/`JobHandler<T>` abstraction**, so a future swap to a real
broker is a new adapter implementing the same interface, not a redesign of call sites.

```kotlin
interface JobQueue<T> {
    fun enqueue(payload: T): String
}

fun interface JobHandler<T> {
    fun handle(job: Job<T>)   // throw to trigger retry/DLQ
}
```

`RedisStreamJobQueue`/`RedisStreamConsumer` (`src/main/kotlin/org/timpeng/chatbot/queue/`)
implement this using Redis Streams' consumer-group primitives: `XADD` to enqueue, `XREADGROUP` to
consume, `XACK` on success, and `XPENDING`/`XCLAIM` to reclaim and retry jobs an idle consumer
never acked — using Redis's own per-message delivery count rather than a hand-rolled attempt
counter. Jobs that exhaust `max-attempts` are moved to a companion `{stream}:dlq` stream instead
of being dropped.

## Options considered

### Option A — Kafka

| | |
|---|---|
| ✅ | High throughput, industry-standard, real partitioned consumer groups |
| ❌ | A whole new service to deploy/operate; this project's own architecture doc already flags Kafka as "overkill for MVP" for Phase 3 |
| ❌ | Offset-commit-per-partition delivery model, not per-message ack — a bigger mental model shift than the other two options |

### Option B — RabbitMQ

| | |
|---|---|
| ✅ | Mature, per-message ack/nack/requeue — closer to what this app actually needs |
| ❌ | Still a new service/dependency to run and operate, for a scale this project doesn't have yet |

### Option C — Redis List (`BRPOPLPUSH`/`LMOVE`)

| | |
|---|---|
| ✅ | Simplest possible implementation, reuses Redis |
| ❌ | No native consumer groups, ack, or delivery-count tracking — retry/DLQ/reclaim-on-crash would all have to be hand-rolled from scratch, which is exactly the complexity a "reduce migration debt" abstraction is supposed to avoid re-doing later |

### Option D — Redis Streams (chosen)

| | |
|---|---|
| ✅ | Reuses already-deployed infra — no new service |
| ✅ | Native consumer groups, per-message `XACK`, and `XPENDING`/`XCLAIM` for crash recovery — the same shape of guarantees RabbitMQ gives, just self-hosted inside Redis |
| ✅ | `spring-boot-starter-data-redis` (already a dependency) supports all of this out of the box — no new library |
| ❌ | Still Redis under the hood: no independent scaling from the cache/session workload already living there |

## Rationale

Redis Streams gets the closest to "real broker" semantics (consumer groups, per-message ack,
crash recovery via reclaim) of any option that doesn't require standing up a new service, which
directly serves the stated goal of minimizing future migration work. Kafka and RabbitMQ remain
the natural next step if/when this app's scale or reliability requirements outgrow what a
single Redis instance can give — at which point only the adapter changes, not `JobHandler`
implementations.

## Known limitations (stated honestly, not oversold)

- **Ack/redelivery semantics aren't 100% portable.** Streams and RabbitMQ are both per-message
  ack/nack; Kafka is offset-commit-based per partition. A future Kafka adapter would have to
  reinterpret "ack" as "commit up to here," which subtly changes redelivery/ordering guarantees.
  The `JobQueue`/`JobHandler` port hides this from *call sites*, not from whoever writes the next
  adapter — that person still needs to understand the target broker's real delivery model.
- **In-process consumer thread only works because deployment is single-instance today**
  (`Dockerfile` + `docker-compose.prod.yml`, no replicas/load balancer anywhere). Horizontal
  scaling will need either multiple consumers in the same Streams consumer group (already
  supported, just not exercised yet) or revisiting this — not a blocker now, flagged for whenever
  `docs/architecture.md`'s "Future Production Architecture" actually happens.
- **No backoff/delay on retry** — a failed job is eligible for redelivery as soon as
  `app.queue.reclaim-idle-ms` elapses, not with any exponential/jittered delay. Fine at today's
  scale; listed below as future work rather than built speculatively.

## Future considerations

| Item | Description |
|---|---|
| Migrate `streamChat` onto the queue | Replace `ChatService.streamChat`'s `CompletableFuture.runAsync` dispatch with `queue.enqueue(...)` + a `JobHandler`, reusing the existing `RedisService.markGenerating`/`updateGeneratingProgress` mechanism to deliver results back to the SSE connection. The natural next use of this infrastructure. |
| Exponential backoff on retry | Delay reclaim eligibility based on attempt count instead of a fixed idle threshold. |
| DLQ inspection tooling | Currently: `XRANGE {stream}:dlq - +` by hand. An admin endpoint or scheduled alert once there's an actual DLQ with real traffic worth watching. |
| Kafka/RabbitMQ adapter | New class implementing `JobQueue<T>` + a consumer wired the same way — if/when scale or ops requirements justify a dedicated broker. |
