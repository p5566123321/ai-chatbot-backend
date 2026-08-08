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

**Not yet wired to any call site.** `ChatService.streamChat` still dispatches the Gemini call via
`CompletableFuture.runAsync` directly — migrating it onto `JobQueue`/`JobHandler` is the next
step (tracked in ADR-006's "Future considerations"), deferred because that dispatch path was just
hardened for resilient SSE reconnect/resume and deserves its own focused change.

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
| Text Splitting | LangChain |
| Storage | PostgreSQL |

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

- API key protection
- Environment variable management
- Rate limiting (future)

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