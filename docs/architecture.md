# System Architecture

## Overview

This project is an AI Chat Backend Service built with Spring Boot.

The system is designed using an iterative architecture approach:

- Start from a simple monolithic MVP
- Gradually evolve into scalable and distributed architecture
- Support future features such as:
    - Streaming response
    - Conversation memory
    - Async processing
    - RAG (Retrieval-Augmented Generation)
    - High concurrency

---

# Architecture Evolution

## Phase 1 — MVP Architecture

### Goals

- Simple REST API
- Integrate with LLM provider
- Persist chat history
- Fast development iteration

### Components

```text
Client
   │
   ▼
Spring Boot API
   │
   ├── Chat Controller
   ├── Chat Service
   ├── LLM Client
   └── PostgreSQL
```

### Request Flow

1. User sends message
2. Controller receives request
3. Service validates and processes prompt
4. LLM Client calls external AI API
5. Response stored into database
6. Return response to client

### Technology Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot |
| Language | Kotlin |
| Database | PostgreSQL |
| ORM | Spring Data JPA |
| Build Tool | Gradle |
| API | REST |
| Container | Docker |

---

# Phase 2 — Streaming + Redis Context

## Goals

- Reduce conversation latency
- Support streaming response
- Store short-term conversation context

## Additional Components

```text
        ┌─────────────┐
        │    Redis    │
        └──────┬──────┘
               │
Client → Spring Boot API → LLM Provider
```

## Design Decisions

### Why Redis?

Redis is used for:

- Short-term conversation memory
- Session caching
- Reducing database reads
- Temporary context storage

### Why Streaming?

Streaming improves:

- User experience
- Perceived response speed
- Real-time interaction

### Possible Technologies

- Spring WebFlux
- Server-Sent Events (SSE)
- WebSocket

---

# Phase 3 — Async Queue

## Goals

- Decouple heavy processing
- Improve scalability
- Prevent request blocking

## Architecture

Decision: **Redis Streams** behind a `JobQueue<T>`/`JobHandler<T>` port, not BullMQ (Node-only,
no JVM client) or a new broker service — see
[ADR-006](decision/006-queue-technology-selection.md) for the full rationale and known
limitations.

```text
         ┌────────────────────────┐
         │      Redis Stream      │
         │ (consumer group, XACK/ │
         │  XCLAIM reclaim, DLQ)  │
         └────────────┬───────────┘
                       │
Client → API → JobQueue.enqueue()
                       │
                       ▼
           RedisStreamConsumer
          (in-process daemon thread)
                       │
                       ▼
                JobHandler<T>
                       │
                       ▼
                 LLM Provider
```

## Implementation status

The port and Redis Streams adapter are built
(`src/main/kotlin/org/timpeng/chatbot/queue/`: `Job.kt`, `RedisStreamJobQueue.kt`,
`RedisStreamConsumer.kt`), with consumer-group-based retry (Redis's own delivery count, no
hand-rolled attempt tracking) and a `{stream}:dlq` dead-letter stream for jobs that exhaust
`app.queue.max-attempts`.

**Wired up for chat.** `ChatService.streamChat` enqueues a `ChatJobPayload` (just the
`conversationId`) instead of dispatching the Gemini call itself; `ChatJobHandler`
(`src/main/kotlin/org/timpeng/chatbot/chat/`), driven by a `RedisStreamConsumer` bean wired in
`ChatQueueConfig`, does the actual `streamGenerate` call and writes chunks back to the client's
`SseEmitter`. Since the emitter itself can't travel through a Redis Stream payload,
`SseEmitterRegistry` — an in-process `conversationId -> SseEmitter` `ConcurrentHashMap` — is how
the consumer thread finds its way back to the connection the controller thread created. This only
works because producer and consumer are the same JVM (true today, single instance); the chosen
path for horizontal scaling is to swap this registry for Redis Pub/Sub — each node subscribes to
the conversationIds it holds a live connection for — rather than changing `JobQueue`/`JobHandler`
themselves. `ChatJobHandler` deliberately never rethrows to trigger the queue's own retry: replaying
`streamGenerate` against an emitter that already sent partial chunks would duplicate/corrupt
output, so every failure mode (LLM error, disconnected client) is still handled terminally inside
the handler, same as before this used the queue.

## Use Cases

- Long-running AI tasks
- Embedding generation
- Background processing
- Retry mechanism

## Queue Technology Evaluation

Full options table (Kafka, RabbitMQ, Redis List, Redis Streams) and rationale:
[ADR-006](decision/006-queue-technology-selection.md).

Current strategy:

- Now: Redis Streams (reuses already-deployed Redis; native consumer groups + per-message ack
  give RabbitMQ-like delivery guarantees without a new service to operate)
- Future scaling: Kafka or RabbitMQ, as a new `JobQueue<T>` adapter, if/when scale or reliability
  needs outgrow a single Redis instance

---

# Phase 4 — RAG Architecture

## Goals

- Improve response accuracy
- Support custom knowledge base
- Reduce hallucination

## Architecture

```text
                ┌─────────────────┐
                │ Vector Database │
                └────────┬────────┘
                         │
User Query → Embedding → Retrieval
                         │
                         ▼
                  Relevant Context
                         │
                         ▼
                    LLM Prompt
```

## RAG Pipeline

1. User uploads documents
2. Documents are chunked
3. Generate embeddings
4. Store embeddings into vector DB
5. Retrieve relevant chunks during chat

## Candidate Technologies

| Purpose | Technology |
|---|---|
| Embedding Model | OpenAI / Gemini |
| Vector DB | pgvector / Pinecone |
| Text Splitting | Custom recursive-character splitter, see [ADR-009](decision/009-rag-text-splitting.md) |
| Storage | PostgreSQL |

## Implementation status

**Built end to end.** Ingestion: `DocumentController`/`DocumentService` (`POST`/`PATCH
/api/documents`) run the upload → `TextSplitter.split` → `EmbeddingProvider.embed` →
`VectorSearchPort.upsertChunk` pipeline synchronously and inline — it never advances
`DocumentStatus` past `PENDING` yet, and moving it behind the job queue (mirroring
`ChatQueueConfig`) is still TODO once ingestion reliably takes longer than a request. Retrieval:
`RagService.buildPrompt` embeds the user's query and calls `VectorSearchPort.findSimilarChunks`
(`PgVectorSearchAdapter`/pgvector, ADR-009's Context notes this and the embedding-model row are
effectively already settled by what got built, even without their own ADR) — the number of
candidates (`top-k`) and the minimum similarity score a chunk must clear are both configurable
(`app.rag.search.top-k`/`similarity-threshold`, env `RAG_SEARCH_TOP_K`/
`RAG_SEARCH_SIMILARITY_THRESHOLD`). `GeminiProvider` calls `RagService.buildPrompt` to augment only
the final user turn before sending it to Gemini. Text splitting is decided and implemented —
`org.timpeng.chatbot.rag.splitting.TextSplitter`/`RecursiveCharacterTextSplitter`, see
[ADR-009](decision/009-rag-text-splitting.md) for why a custom implementation over LangChain/
`langchain4j`.

---

# Phase 5 — JWT Authentication

## Goals

- Scope every conversation to the account that created it
- Close the gap ADR-002/ADR-004 flagged and deferred: a leaked `conversationId` alone used to be
  enough to read or continue anyone's conversation

## Architecture

```text
Client
   │  Authorization: Bearer <JWT>
   ▼
JwtAuthenticationFilter  ──▶  SecurityContext (userId)
   │
   ▼
SecurityConfig (stateless, permitAll only for /api/auth/** + actuator health/prometheus)
   │
   ▼
Controller (@CurrentUserId) ──▶ ConversationService.requireOwnedConversation
                                     │
                                     ├─ unknown / not-owned / pre-auth orphan → 404
                                     └─ owned → proceed
```

## Implementation status

**Built.** Full design and the options weighed (self-issued JWT vs. an external IdP, access-only
vs. refresh tokens, backfilling pre-auth conversations vs. orphaning them) are in
[ADR-007](decision/007-jwt-authentication.md); this section only summarizes the shape.

- `POST /api/auth/register` / `POST /api/auth/login` (`org.timpeng.chatbot.auth`) — a new `User`
  table (Postgres, BCrypt-hashed passwords), issuing an access-only JWT on login (no refresh
  token, no server-side session — `RedisSessionConfig`/`spring-session-data-redis` were removed as
  dead weight once this landed, since nothing had ever used `HttpSession`).
- `JwtAuthenticationFilter` populates `SecurityContext` from the `Authorization` header ahead of
  `SecurityConfig`'s `authorizeHttpRequests` rules; `@CurrentUserId` (a
  `HandlerMethodArgumentResolver`) is how controllers read the resulting `userId` back out.
- `Conversation.ownerId` (nullable, never backfilled) is stamped at creation and checked by
  `ConversationService.requireOwnedConversation` on every other conversation-scoped endpoint —
  `ConversationController.getMessages`, and `ChatController.chat`/`streamChat`/`streamStatus` via
  `ChatService`. A conversation that exists but isn't the caller's 404s exactly like an unknown
  id, same reasoning ADR-004 already used for unresolvable IDs: don't let the response distinguish
  "doesn't exist" from "not yours."
- Not yet built (tracked in ADR-007's "Future considerations"): refresh/revocable tokens, and a
  role concept — the latter specifically to admin-gate `GET /api/admin/queues/chat/dlq`
  (`ChatDlqController`), which today is authenticated but not admin-restricted.

---

# Phase 6 — User-Configurable Model Parameters

## Goals

- Let each user pick their own chat model and bring their own API key (BYOK)
- Let each user tune their own history window size (currently a global
  `app.conversation.cache.max-msg`)
- Do this without weakening the security/observability guarantees Phases 1-5 already built

## Architecture

```text
Client
   │  model, api key, history window
   ▼
UserSettingsController ──▶ UserSettings (Postgres, keyed by userId)
                              │  api key stored encrypted at rest
                              ▼
ChatService ──▶ LlmProviderFactory.resolve(userSettings)
                              │
                              ├─ provider + model + key resolved per request
                              │  (replaces today's startup-time
                              │  @ConditionalOnProperty bean selection
                              │  in LlmConfig)
                              ▼
                        LlmProvider (Gemini / other)
```

## Design considerations

- **Per-request provider resolution, not per-instance.** Today `LlmConfig` picks a single
  `LlmProvider` bean at startup via `app.llm.provider`. Supporting per-user model choice means
  resolving provider + model + key per request instead — a factory/strategy call inside
  `ChatService`, not a Spring conditional bean. `FakeLlmProvider` keeps working as one of the
  resolvable options (e.g. for a "test without spending your own quota" mode).
- **BYOK key storage is its own security surface**, not a bolt-on column: encryption at rest, a
  key-management story for the encryption key itself, and keeping user API keys out of logs,
  error messages, and Micrometer tags. Worth its own ADR before implementation, the same way
  auth got ADR-007.
- **Model choice has to cover the embedding model, not just the chat model**, once Phase 4 (RAG)
  is built — this is the concrete reason this phase is sequenced after RAG rather than before it
  (see `docs/roadmap.md`).
- **History window size is the low-risk exception.** Moving `app.conversation.cache.max-msg` from
  global config to a per-user/per-conversation value touches `ConversationHistoryService` and the
  Redis-backed list size in `ConversationCacheService`, but doesn't involve secret storage or
  provider resolution — it can be pulled forward ahead of the rest of this phase if useful.

## Implementation status

Not yet built. Planned after Phase 4 (RAG) — see `docs/roadmap.md` Phase 6 for the sequencing
rationale.

---

# Deployment Architecture

## Local Development

```text
Docker Compose
 ├── Spring Boot
 ├── PostgreSQL
 └── Redis
```

## Future Production Architecture

```text
NGINX
   │
   ▼
Load Balancer
   │
   ▼
Spring Boot Instances
   │
   ├── PostgreSQL
   ├── Redis
   ├── Queue
   └── Vector DB
```

---

# Non-Functional Requirements

## Scalability

- Stateless API design
- Horizontal scaling support
- Async processing

## Reliability

- Retry mechanism
- Error handling
- Request timeout protection

## Security

- JWT authentication + per-user conversation ownership (Phase 5, ADR-007)
- API key protection
- Environment variable management
- Rate limiting (future)
- Role-based admin gate for `/api/admin/**` (future — see ADR-007)

## Observability

- Prometheus — `/actuator/prometheus`, scraped per `observability/prometheus` config
- Grafana — dashboards in `observability/grafana`
- OpenTelemetry (future)

---

# Future Improvements

- Multi-model routing
- AI agent workflow
- Tool calling
- Multi-tenant architecture
- Kubernetes deployment
- Distributed tracing

---

# Design Philosophy

This project intentionally starts simple and evolves incrementally.

The goal is to avoid premature over-engineering while still maintaining clear scalability paths.