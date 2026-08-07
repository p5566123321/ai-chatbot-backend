# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kotlin/Spring Boot backend for an AI chatbot, backed by Postgres (durable storage) and Redis
(short-term conversation cache + Spring Session store), calling Gemini via `google-genai`. Built
iteratively — see `docs/architecture.md` and `docs/roadmap.md` for the phased plan (currently in
Phase 2: streaming + Redis context) and `docs/decision/*.md` for the ADRs behind current design
choices.

## Commands

```bash
# Run the app (needs .env — copy .env.example and fill in GOOGLE_API_KEY, DB_*, REDIS_*, GEMINI_MODEL)
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
`ConversationHistoryService` (cache-aside reads) → `RedisService` / JPA repositories →
`LlmProvider` (Gemini). Full sequence diagram: `docs/chatFlow.md`.

Conversations are backend-generated (ADR-004, superseding ADR-002): `POST /api/conversations`
creates a `Conversation` row and returns its `uuid`; every other endpoint is scoped under
`/api/conversations/{conversationId}/...`. A `conversationId` that doesn't resolve to a row is a
`404` (`ConversationNotFoundException`) — it is intentionally *not* auto-created on miss, since
that used to mask stale/typo'd client IDs (see ADR-004's "Context").

### Cache-aside conversation history (ADR-003)

`ConversationHistoryService.getHistory()` is the single read path for conversation context:
1. Try Redis (`chat:conversation:{id}` list) first.
2. On a miss, load the last `app.conversation.cache.max-msg` messages from Postgres and backfill
   Redis so the next read in the same session is a hit.
3. Redis is a pure cache (30 min TTL, `app.conversation.cache.ttl-min`) — Postgres is always the
   source of truth; nothing is lost if Redis is flushed.

`app.conversation.cache.enabled` (env `CONVERSATION_CACHE_ENABLED`) can force every read through
Postgres — this exists specifically to A/B the Redis benefit under load (see
`docs/decision/005-redis-vs-db-latency-benchmark.md` and `benchmark/k6-history-latency.js`), not
as a normal runtime toggle.

### Write-after-commit caching

`ConversationService.saveMessage` persists to Postgres inside `@Transactional`, then registers a
`TransactionSynchronization` so the Redis write only happens `afterCommit`. This avoids caching a
message whose DB write gets rolled back. Follow this pattern for any new write path that touches
both Postgres and Redis.

### Streaming chat

`ChatService.streamChat` validates the conversation and saves the user message synchronously (so
an unknown `conversationId` still surfaces as a normal 404 before any async work starts), then
runs the actual Gemini streaming call in `CompletableFuture.runAsync` off the servlet thread.
While a stream is in flight, `RedisService.markGenerating`/`updateGeneratingProgress` (throttled to
every `PROGRESS_FLUSH_INTERVAL_MS`) let a disconnected/reconnecting client poll
`GET .../messages/stream/status` for partial progress instead of needing to hold the SSE
connection open. On cancellation or failure mid-stream, the partial response is persisted with a
`[回覆中斷]` marker rather than discarded.

### LLM provider abstraction

`LlmProvider` is the interface (`generate`, `streamGenerate`); `GeminiProvider` is the only
implementation, gated by `@ConditionalOnProperty(app.llm.provider = "gemini")` so a new provider
can be added as a sibling `@Service` without touching call sites. `Role.geminiName` maps the
internal `USER/ASSISTANT/SYSTEM` enum to Gemini's `user/model` role strings.

### Metrics

Micrometer/Prometheus timers are threaded through the hot path deliberately, not just at the
edges: `history.cache.time` (tagged `result=hit|miss|disabled`), `history.db.fallback.time`,
`llm.generate.time` / `llm.stream_generate.time` (tagged `outcome=success|timeout|failure|cancelled`),
`llm.stream_generate.first_token_time`, plus `@Timed("total.chat.time")` on `ChatService.chat`.
`/actuator/prometheus` is exposed. When adding a new external call or cache path, tag its timer
with an outcome/result dimension the same way rather than a bare duration — that's what the
existing Grafana dashboard (`observability/`) and the benchmark protocol in ADR-005 rely on.

## Data model (ADR-001)

`Conversation` (auto-increment `id` + client-facing `uuid`) 1—N `Message` (`role`: USER/ASSISTANT/
SYSTEM, `content: TEXT`, `createdAt`). `SYSTEM` is reserved for future system-prompt/RAG injection
and isn't sent to Gemini (`GeminiProvider.buildContents` filters to USER/ASSISTANT only).

## Error handling

All exceptions are mapped centrally in `GlobalExceptionHandler` to a single `ErrorResponse` shape
— `ConversationNotFoundException` → 404, `IllegalArgumentException`/malformed body → 400,
`LlmException` → 503 (`LLM_UNAVAILABLE`), everything else → 500. Add new domain exceptions there
rather than handling them ad hoc in controllers.

## Benchmarking

`benchmark/k6-history-latency.js` + `scripts/seed-benchmark-data.sh` / `cleanup-benchmark-data.sh`
drive load directly at `ConversationHistoryService.getHistory` (seeding Postgres directly, bypassing
the LLM) to compare Redis-cached vs. `CONVERSATION_CACHE_ENABLED=false` latency. Full protocol,
including the observability stack (`docker-compose.observability.yml`, Prometheus/Grafana) and
`scripts/render-prometheus-config.sh`, is in `docs/decision/005-redis-vs-db-latency-benchmark.md`.