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
- **Backoff has no jitter.** Retry eligibility now doubles per prior delivery (see "Future
  considerations" below), but it's deterministic — many jobs failing in the same window back off
  in lockstep rather than spreading out. Not a real concern at today's traffic; would matter if a
  large batch of jobs ever failed simultaneously and all reclaimed at the exact same moment.

## Future considerations

| Item | Description |
|---|---|
| ~~Migrate `streamChat` onto the queue~~ | **Done.** `ChatService.streamChat` now enqueues a `ChatJobPayload`; `ChatJobHandler` + `ChatQueueConfig` (`src/main/kotlin/org/timpeng/chatbot/chat/`) do the actual `streamGenerate` call and deliver results back to the SSE connection. See the "Emitter delivery" addendum below for how. |
| ~~Exponential backoff on retry~~ | **Done.** `RedisStreamConsumer.reclaimStuckEntries` now computes each pending entry's reclaim-eligibility window via `reclaimBackoffMs(reclaimIdleMs, deliveryCount)` — doubles per prior delivery (Redis's own per-message delivery count), capped at 10 minutes. First delivery still uses the plain `reclaimIdleMs` value, so this is a behavior-preserving change for the common case. |
| ~~DLQ inspection tooling~~ | **Partly done.** `GET /api/admin/queues/chat/dlq` (`ChatDlqController` + `DlqReader`, `src/main/kotlin/org/timpeng/chatbot/queue/DlqReader.kt`) lists recent dead-lettered entries and a total count, read-only, no auth (matches the app's current no-Spring-Security posture everywhere else). A scheduled alert on DLQ depth is still manual/not built. |
| Kafka/RabbitMQ adapter | New class implementing `JobQueue<T>` + a consumer wired the same way — if/when scale or ops requirements justify a dedicated broker. |

## Addendum: emitter delivery (streamChat migration)

An `SseEmitter` can't be serialized into a job payload, so `ChatJobPayload` carries only
`conversationId`; `ChatJobHandler` needs another way to find the connection the controller thread
created. Decision: **`SseEmitterRegistry`, an in-process `ConcurrentHashMap<conversationId,
SseEmitter>`**, populated by `ChatService.streamChat` before enqueue and read by `ChatJobHandler`
on delivery.

This is explicitly a single-instance-only mechanism — it only works because the producer
(controller thread) and consumer (`RedisStreamConsumer`'s daemon thread) are always the same JVM,
which was already this ADR's "known limitation" for the consumer thread itself, so it isn't a new
constraint, just the same one applied to emitter delivery too. **When this app is horizontally
scaled, the chosen path is Redis Pub/Sub**, not a redesign of `JobQueue`/`JobHandler`: each node
subscribes to a channel per `conversationId` it's currently holding an open SSE connection for,
and `ChatJobHandler` publishes chunks to that channel instead of writing straight into a local
`SseEmitter` — a node other than the one that enqueued the job could then pick up the delivery.
Not built now because it isn't needed at single-instance scale and would add a second Redis
subscription mechanism (on top of the Streams consumer group) for no current benefit.
