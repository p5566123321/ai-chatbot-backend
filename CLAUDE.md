# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kotlin/Spring Boot backend for an AI chatbot, backed by Postgres (durable storage) and Redis
(short-term conversation cache), calling Gemini via `google-genai`. Built iteratively — see
`docs/architecture.md` and `docs/roadmap.md` for the phased plan (Phases 1-3 and 5 are built:
REST/LLM/Postgres, streaming/Redis context, the queue, and JWT auth; Phase 4/RAG hasn't started)
and `docs/decision/*.md` for the ADRs behind current design choices.

## Commands

```bash
# Run the app (needs .env — copy .env.example and fill in GOOGLE_API_KEY, DB_*, REDIS_*,
# GEMINI_MODEL, JWT_SECRET — the last must be a real random value, e.g. `openssl rand -base64 64`;
# anything shorter than 256 bits fails HS-family key validation at startup, by design)
./gradlew bootRun

# Build
./gradlew build

# Run all tests
./gradlew test

# Run a single test class / method
./gradlew test --tests "org.timpeng.chatbot.chat.ChatServiceTest"
./gradlew test --tests "org.timpeng.chatbot.chat.ChatServiceTest.methodName"

# Local infra (Postgres + Redis)
docker compose up -d
```

Env vars are loaded from `.env` by IntelliJ / your run config; when running shell scripts (seed,
cleanup, prometheus render) `source scripts/load-env.sh` first so `${...}` values are exported into
the shell.

Tests use JUnit 5 + MockK (`mockk()`/`relaxed = true`) + SpringMockK — see any `*Test.kt` for the
pattern of mocking collaborators and constructing the class under test directly rather than using
`@SpringBootTest` except for the SSE integration test.

## Architecture

### Request flow

`ChatController` / `ConversationController` → `ChatService` / `ConversationService` →
`ConversationHistoryService` (cache-aside reads) → `ConversationCacheService`/`GeneratingStatusService`
(Redis) / JPA repositories → `LlmProvider` (Gemini). Full sequence diagram: `docs/chatFlow.md`.

Conversations are backend-generated (ADR-004, superseding ADR-002): `POST /api/conversations`
creates a `Conversation` row and returns its `uuid`; every other endpoint is scoped under
`/api/conversations/{conversationId}/...`. A `conversationId` that doesn't resolve to a row is a
`404` (`ConversationNotFoundException`) — it is intentionally *not* auto-created on miss, since
that used to mask stale/typo'd client IDs (see ADR-004's "Context").

### Authentication and conversation ownership (ADR-007)

`org.timpeng.chatbot.auth`: self-issued, access-only JWTs (no refresh token) via
`POST /api/auth/register` / `POST /api/auth/login`, backed by a Postgres `User` table
(BCrypt-hashed passwords). `JwtAuthenticationFilter` populates `SecurityContext` from the
`Authorization: Bearer` header ahead of `SecurityConfig`'s rules (`/api/auth/**` and
`/actuator/health`/`/actuator/prometheus` are `permitAll`, everything else under `/api/**`
requires a valid token). It's deliberately **not** a `@Component` — see its own kdoc for why
(double filter registration + breaking `@WebMvcTest` slices that don't load `@Service` beans).
Controllers pull the caller's `userId` via `@CurrentUserId`, a `HandlerMethodArgumentResolver`
registered in `WebConfig`.

`Conversation.ownerId` (nullable, never backfilled for pre-auth rows) is stamped at creation and
enforced by `ConversationService.requireOwnedConversation(conversationId, ownerId)` on every other
conversation-scoped endpoint. A conversation that exists but isn't the caller's 404s exactly like
an unknown id — same "don't let the response distinguish those two cases" reasoning ADR-004 uses
for unresolvable IDs. Any new endpoint that takes a `conversationId` must call this before doing
anything with it; it's not automatic.

### Cache-aside conversation history (ADR-003)

`ConversationHistoryService` is an interface with two implementations, wired conditionally on
`app.conversation.cache.enabled` (mirrors the `LlmProvider`/`GeminiProvider` conditional-bean
pattern below):
- `DatabaseConversationHistoryService` — always registered; reads the last
  `app.conversation.cache.max-msg` messages straight from Postgres.
- `CachedConversationHistoryService` — `@Primary` + `@ConditionalOnProperty` decorator, registered
  whenever caching is enabled (the default). Tries Redis (`chat:conversation:{id}` list) first; on
  a miss it delegates to `DatabaseConversationHistoryService` and backfills Redis so the next read
  in the same session is a hit.

Redis is a pure cache (30 min TTL, `app.conversation.cache.ttl-min`) — Postgres is always the
source of truth; nothing is lost if Redis is flushed.

`app.conversation.cache.enabled=false` (env `CONVERSATION_CACHE_ENABLED`) drops the
`CachedConversationHistoryService` bean entirely, leaving `DatabaseConversationHistoryService` as
the sole implementation — this exists specifically to A/B the Redis benefit under load (see
`docs/decision/005-redis-vs-db-latency-benchmark.md` and `benchmark/k6-history-latency.js`), not
as a normal runtime toggle. Adding a third caching strategy means adding another
`ConversationHistoryService` implementation, not branching inside an existing one.

### Redis usage is split by responsibility, not bundled into one service

`org.timpeng.chatbot.redis` has two single-purpose `@Service` classes instead of one grab-bag
Redis facade:
- `ConversationCacheService` — the `chat:conversation:{id}` list used by
  `CachedConversationHistoryService`/`ConversationService` (history read/write, JSON via
  `ObjectMapper`).
- `GeneratingStatusService` — the `chat:generating:{id}` string key used only by `ChatService` to
  track in-flight SSE streams (see below). Different TTL policy, no JSON involved.

Keep this split when adding new Redis-backed behavior — a new concern gets a new class, not a new
method bolted onto one of these.

### Write-after-commit caching

`ConversationService.saveMessage` persists to Postgres inside `@Transactional`, then registers a
`TransactionSynchronization` so the `ConversationCacheService` write only happens `afterCommit`.
This avoids caching a message whose DB write gets rolled back. Follow this pattern for any new
write path that touches both Postgres and Redis.

### Chat message embedding

`MessageEmbeddingService.embedAsync`, called from the same `afterCommit` hook as the Redis cache
write above, embeds every USER/ASSISTANT message into `messages.embedding` (`vector(768)`,
`V5__add_message_embedding_column.sql`) through the existing chat endpoints — no new endpoint,
this rides `saveMessage`. Reuses the same `EmbeddingProvider` (Gemini, `app.llm.gemini.embedding-*`)
already used for `document_chunk`; writes go through raw JDBC (`MessageEmbeddingJdbcRepository`),
same `CAST(? AS vector)` split as `DocumentChunkJdbcRepository`. Unlike `DocumentChunkJpaEntity`,
`Message` deliberately does **not** map `embedding` as a JPA field at all — `messageRepository.save`
runs on every chat turn, and Hibernate has no built-in JDBC type for pgvector's `vector` column;
binding even a null `PGvector` through JPA on that path fails the INSERT outright. Leaving the
column unmapped is safe (`ddl-auto=validate` doesn't require every DB column to be entity-mapped).

Unlike the cache write, this is **not** inline: it's gated by a per-user switch
(`users.message_embedding_enabled`, `V6__add_user_message_embedding_flag.sql`, default false) set
via `PATCH /api/users/me/message-embedding` (`UserController`/`UserService`) — a UI-controlled
setting, not an `app.*` config value, so `embedAsync` reads it per message off the message's
conversation owner (`Conversation.ownerId`; a pre-auth conversation with no owner is skipped
entirely). Once the switch check passes, the actual embed+persist runs on its own
`CoroutineScope(Dispatchers.IO + SupervisorJob())` and is never awaited — a Gemini embed call is a
real external round-trip, and `saveMessage` sits on the hot chat path (`ChatService.chat`'s
response, `ChatJobHandler`'s SSE stream), so nothing there can block on it. Failures are logged and
swallowed inside the launched coroutine; there's no caller left to hand them to by the time the
embed finishes. No retrieval is wired up yet — `RagService` still only searches `document_chunk`.

### Streaming chat

`ChatService.streamChat` validates the conversation and saves the user message synchronously (so
an unknown `conversationId` still surfaces as a normal 404 before any async work starts), registers
the `SseEmitter` in `SseEmitterRegistry` keyed by `conversationId`, then enqueues a `ChatJobPayload`
onto the chat job queue (`ChatQueueConfig`, `org.timpeng.chatbot.queue`'s Redis Streams
`JobQueue`/`RedisStreamConsumer` — see `docs/decision/006-queue-technology-selection.md`) instead
of running the Gemini call itself. `ChatJobHandler`, driven by `RedisStreamConsumer`'s own daemon
thread, does the actual `streamGenerate` call: it re-derives the message history from
`conversationId` alone (a Redis cache hit, since the user message was already saved+cached before
enqueue — the job payload deliberately carries no message data), looks the emitter back up via
`SseEmitterRegistry`, and streams chunks to it. `SseEmitterRegistry` is a plain in-process
`ConcurrentHashMap` — this only works because producer and consumer are the same JVM, true today
(single instance); horizontally scaling would need Redis Pub/Sub instead (each node subscribes to
the conversationIds it holds a live connection for) rather than a direct map lookup.

While a stream is in flight, `GeneratingStatusService.markGenerating`/`updateGeneratingProgress`
(throttled to every `PROGRESS_FLUSH_INTERVAL_MS`) let a disconnected/reconnecting client poll
`GET .../messages/stream/status` for partial progress instead of needing to hold the SSE
connection open. On cancellation or failure mid-stream, the partial response is persisted with a
`[回覆中斷]` marker rather than discarded — `ChatJobHandler` never rethrows to trigger the queue's
own retry, since replaying `streamGenerate` against an emitter that already sent partial chunks
would duplicate/corrupt output rather than recover anything; every failure mode is handled
terminally inside the handler instead.

### LLM provider abstraction

`LlmProvider` is the interface (`generate`, `streamGenerate`), each gated by
`@ConditionalOnProperty(app.llm.provider = "...")` so a new implementation is a sibling `@Service`
that doesn't touch call sites (though `LlmConfig`'s `@Bean llmProvider()` selector `when` block
does need a new branch — it doesn't discover providers automatically). Two implementations exist:
- `GeminiProvider` (`app.llm.provider=gemini`, the default) — the real thing.
- `FakeLlmProvider` (`app.llm.provider=fake`, env `LLM_PROVIDER=fake`) — deterministic,
  no-external-call responses for load-testing the queue pipeline in isolation from real LLM
  latency/cost (mirrors why `k6-history-latency.js` avoids the chat endpoints entirely — Gemini's
  multi-second latency would drown out whatever's actually being measured). Publishes the same
  `llm.*` metrics as `GeminiProvider` (tagged `provider=fake`) so dashboards don't need separate
  panels; chunk pacing is configurable via `app.llm.fake.chunk-delay-ms` (env
  `FAKE_LLM_CHUNK_DELAY_MS`).

`Role.geminiName` maps the internal `USER/ASSISTANT/SYSTEM` enum to Gemini's `user/model` role
strings.

`GeminiProvider.buildGenerationConfig` reads per-user `GenerateContentConfig` overrides
(`User.geminiSettings`/`GeminiSettings` — systemInstruction/temperature/topP/topK/candidateCount/
maxOutputTokens, `V7__add_user_gemini_settings.sql`) via `ownerId`, same pattern as `RagService`
being read inside `buildContents` rather than threaded down from the chat endpoints. Every field
is independently nullable; `null` means "don't set this on the request" (Gemini's own default
applies), and an all-null `GeminiSettings` makes `buildGenerationConfig` return `null` outright —
identical to this class's behavior before the feature existed. Set via
`PATCH /api/users/me/gemini-settings` (`UserController`) as a full replace, not a partial merge.
`candidateCount` is passed through but not consumed anywhere yet — `generate` only ever reads
`response.text()` (the first candidate).

### Metrics

Micrometer/Prometheus timers are threaded through the hot path deliberately, not just at the
edges: `history.cache.time` (tagged `result=hit|miss|disabled`), `history.db.fallback.time`,
`llm.generate.time` / `llm.stream_generate.time` (tagged `outcome=success|timeout|failure|cancelled`),
`llm.stream_generate.first_token_time`, `auth.login.time` (tagged `outcome=success|bad_credentials`),
plus `@Timed("total.chat.time")` on `ChatService.chat`.
`/actuator/prometheus` is exposed. When adding a new external call or cache path, tag its timer
with an outcome/result dimension the same way rather than a bare duration — that's what the
existing Grafana dashboard (`observability/`) and the benchmark protocol in ADR-005 rely on.

## Data model (ADR-001)

`Conversation` (auto-increment `id` + client-facing `uuid`) 1—N `Message` (`role`: USER/ASSISTANT/
SYSTEM, `content: TEXT`, `createdAt`). `SYSTEM` is reserved for future system-prompt/RAG injection
and isn't sent to Gemini (`GeminiProvider.buildContents` filters to USER/ASSISTANT only).

Schema is Flyway-managed (ADR-008): `src/main/resources/db/migration/Vn__*.sql`, applied
automatically on startup ahead of Hibernate's `ddl-auto=validate` check. Add a new `Vn__*.sql` for
any entity change — Hibernate no longer generates DDL itself, so editing an `@Entity` alone won't
touch the database.

## Error handling

All exceptions are mapped centrally in `GlobalExceptionHandler` to a single `ErrorResponse` shape
— `ConversationNotFoundException` → 404, `IllegalArgumentException`/malformed body → 400,
`LlmException` → 503 (`LLM_UNAVAILABLE`), `UserAlreadyExistsException` → 409,
`BadCredentialsException` → 401 (wrong login credentials specifically; a missing/invalid bearer
token on any other endpoint 401s via `SecurityConfig`'s `AuthenticationEntryPoint` instead, before
a request ever reaches a controller), everything else → 500. Add new domain exceptions there
rather than handling them ad hoc in controllers.

## Benchmarking

`benchmark/k6-history-latency.js` + `scripts/seed-benchmark-data.sh` / `cleanup-benchmark-data.sh`
drive load directly at `ConversationHistoryService.getHistory` (seeding Postgres directly, bypassing
the LLM) to compare Redis-cached vs. `CONVERSATION_CACHE_ENABLED=false` latency. Full protocol,
including the observability stack (`docker-compose.observability.yml`, Prometheus/Grafana) and
`scripts/render-prometheus-config.sh`, is in `docs/decision/005-redis-vs-db-latency-benchmark.md`.