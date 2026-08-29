# AI Chatbot — Backend

A Kotlin / Spring Boot backend for an LLM-powered chatbot. It is built iteratively toward a
scalable, low-latency system: a monolithic REST MVP that has since grown streaming responses, a
Redis-backed conversation cache, an async job queue, JWT auth with per-user conversation
ownership, a RAG pipeline over pgvector, and per-user model configuration (model choice, BYOK,
generation parameters, history window).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | REST API, Gemini integration, Postgres persistence | Done |
| 2 | Streaming (SSE), Redis short-term conversation context | Done |
| 3 | Async job queue (Redis Streams) for chat generation | Done |
| 4 | RAG — document ingestion, embeddings, pgvector retrieval | Done |
| 5 | JWT authentication + per-user conversation ownership | Done |
| 6 | Per-user model choice, BYOK, generation params, history window | Done |

See [`docs/roadmap.md`](docs/roadmap.md) and [`docs/architecture.md`](docs/architecture.md) for
detail, and [`docs/decision/`](docs/decision) for the ADRs behind current design choices.

## Tech stack

- **Language / runtime** — Kotlin 2.3, JDK 25
- **Framework** — Spring Boot 4 (Web MVC, Security, Validation, Actuator, Data JPA, Data Redis)
- **Database** — PostgreSQL 18 with the `pgvector` extension; schema managed by Flyway
- **Cache / queue** — Redis 8 (conversation cache, generating-status keys, Redis Streams job queue)
- **LLM** — Gemini via `com.google.genai:google-genai`
- **Auth** — self-issued access-only JWT (`io.jsonwebtoken` / jjwt)
- **Observability** — Micrometer + Prometheus, Grafana dashboards
- **Testing** — JUnit 5, MockK, SpringMockK
- **Build** — Gradle (Kotlin DSL)

## Getting started

### Prerequisites

- JDK 25
- Docker (for local Postgres + Redis)
- A Google Gemini API key

### 1. Configure environment

```bash
cp .env.example .env
```

Fill in at least:

| Variable | Notes |
|---|---|
| `GOOGLE_API_KEY` | Gemini API key |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Postgres connection (default `jdbc:postgresql://localhost:5432/ai_chat`) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DB` | Redis connection |
| `GEMINI_MODEL` | e.g. `gemini-2.5-flash` |
| `JWT_SECRET` | HMAC signing key — must be ≥ 256 bits, e.g. `openssl rand -base64 64`; startup fails otherwise |
| `BYOK_ENCRYPTION_KEY` | AES-256 key for encrypting user-supplied Gemini keys at rest — must decode to exactly 32 bytes, e.g. `openssl rand -base64 32` |

The full list, with defaults and inline rationale, is in [`.env.example`](.env.example) and
[`src/main/resources/application.yaml`](src/main/resources/application.yaml).

### 2. Start local infrastructure

```bash
docker compose up -d          # Postgres (pgvector) + Redis; override file publishes 5432
```

Flyway applies `src/main/resources/db/migration/Vn__*.sql` automatically on startup, ahead of
Hibernate's `ddl-auto=validate` check.

### 3. Run the app

IntelliJ run configs load `.env` automatically. From a plain shell, export it first:

```bash
source scripts/load-env.sh
./gradlew bootRun
```

The API listens on `http://localhost:8080` (`SERVER_PORT`).

## Configuration highlights

Behaviour is driven by `app.*` properties (see `application.yaml`); notable toggles:

- `app.llm.provider` (`LLM_PROVIDER`) — `gemini` (default) or `fake`, a deterministic
  no-external-call provider for load-testing the queue pipeline.
- `app.conversation.cache.enabled` (`CONVERSATION_CACHE_ENABLED`) — drops the Redis cache-aside
  decorator, leaving Postgres-only history reads. Exists to A/B the Redis benefit under load, not
  as a normal runtime toggle.
- `app.rag.search.top-k` / `similarity-threshold` — pgvector retrieval tuning.
- Per-user overrides (model, BYOK key, generation params, RAG on/off, message embedding,
  history window) are set through `/api/users/me/*` endpoints, not config.

## API overview

All `/api/**` routes except `/api/auth/**` require `Authorization: Bearer <JWT>`.
`/actuator/health` and `/actuator/prometheus` are open.

| Method & path | Purpose |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | Account creation, login (returns access JWT) |
| `GET /api/users/me` | Current user + settings |
| `PATCH /api/users/me/{gemini-settings,gemini-api-key,rag-enabled,message-embedding,history-max-messages}` | Per-user configuration |
| `GET /api/users/me/gemini-models` | Allowed chat models (whitelist) |
| `POST /api/conversations` | Create a conversation, returns its `uuid` |
| `GET /api/conversations/{id}/messages` | Conversation history |
| `POST /api/conversations/{id}/messages` | Send a message, get a full response |
| `POST /api/conversations/{id}/messages/stream` | Send a message, stream the response over SSE |
| `GET /api/conversations/{id}/messages/stream/status` | Poll partial progress for an in-flight stream |
| `GET/POST/PATCH/DELETE /api/documents` | RAG document ingestion and management |
| `GET /api/admin/queues/chat/dlq` | Chat job dead-letter queue (authenticated; not yet admin-gated) |

A conversation that does not exist, or is not owned by the caller, returns `404` in both cases —
the response deliberately does not distinguish them (ADR-004 / ADR-007).

Request-flow sequence diagram: [`docs/chatFlow.md`](docs/chatFlow.md).

## Testing

```bash
./gradlew test                                          # all tests
./gradlew test --tests "org.timpeng.chatbot.chat.ChatServiceTest"
./gradlew test --tests "org.timpeng.chatbot.chat.ChatServiceTest.methodName"
```

Tests mock collaborators with MockK and construct the class under test directly, rather than
`@SpringBootTest`, except for the SSE integration test.

## Benchmarking & observability

- `benchmark/k6-history-latency.js` with `scripts/seed-benchmark-data.sh` /
  `cleanup-benchmark-data.sh` drives load directly at `ConversationHistoryService.getHistory` to
  compare Redis-cached vs. `CONVERSATION_CACHE_ENABLED=false` latency. Full protocol:
  [`docs/decision/005-redis-vs-db-latency-benchmark.md`](docs/decision/005-redis-vs-db-latency-benchmark.md).
- `docker-compose.observability.yml` brings up Prometheus + Grafana; config is rendered by
  `scripts/render-prometheus-config.sh` and lives under `observability/`.
- Hot-path timers (`history.cache.time`, `llm.generate.time`, `llm.stream_generate.time`,
  `auth.login.time`, `total.chat.time`, …) are exposed at `/actuator/prometheus`.

## Deployment

- **Container** — multi-stage [`Dockerfile`](Dockerfile) (layered Spring Boot jar, non-root user,
  built-in `/actuator/health` HEALTHCHECK).
- **Compose** — `docker-compose.prod.yml` builds the image and wires it to Postgres.
- **AWS** — deployed to ECS Fargate; infrastructure is managed with Terraform (kept outside this
  repo).

## Further reading

- [`CLAUDE.md`](CLAUDE.md) — detailed architecture notes (request flow, caching, auth, streaming,
  LLM abstraction, RAG, metrics, data model, error handling)
- [`docs/architecture.md`](docs/architecture.md) — phase-by-phase architecture evolution
- [`docs/roadmap.md`](docs/roadmap.md) — roadmap and phase status
- [`docs/decision/`](docs/decision) — ADRs 001–010
