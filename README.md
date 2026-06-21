# AI Chatbot

A LLM powered Chatbot base on Spring Boot.The target includes scalable, highly available, and low-latency
chatbot system.

## Technology Stack

### Backend
- Kotlin
- Spring Boot

### Database
- PostgreSQL (planned)
- Redis (planned)

### AI / LLM
- Gemini API
- Vector Database (planned)

### Async Processing
- RabbitMQ (planned)

### Infrastructure
- Docker (planned)
- Docker Compose (planned)

### Observability
- Prometheus (planned)
- Grafana (planned)

### Testing
- JUnit 5
- MockK

### DevOps
- GitHub Actions (planned)

## System Architecture

```mermaid
graph TD
User --> Frontend
Frontend --> API
API --> Redis
API --> PostgreSQL
```

## Getting Started

```bash
# Unix like:
./gradlew bootRun
# Windows:
 ./gradlew.bat bootRun
```

## Roadmap

### Phase 1 — MVP
- REST API
- LLM integration
- PostgreSQL persistence
- Docker Compose setup

### Phase 2 — Streaming & Context Management
- Streaming response (SSE/WebSocket)
- Redis conversation context cache
- Session management

### Phase 3 — Async Processing
- RabbitMQ job queue
- Background task processing
- Retry / failure handling

### Phase 4 — RAG Pipeline
- Embedding generation
- Vector database integration
- Semantic retrieval

### Phase 5 — Monitoring & Optimization
- Metrics collection
- Structured logging
- Performance optimization
- Load testing

## Architecture Evolution

```text
Monolith MVP
→ Stateful caching
→ Async processing
→ Retrieval pipeline
→ Production observability
```


## Setup

1. Download the Tailwind CLI for your OS from:
   https://github.com/tailwindlabs/tailwindcss/releases/latest

2. Rename it and make it executable:
```bash
   mv tailwindcss-<your-os> tailwindcss
   chmod +x tailwindcss   # macOS/Linux only
```

3. Run the watcher during development:
```bash
   ./tailwindcss -i src/main/resources/static/css/input.css \
                 -o src/main/resources/static/css/output.css --watch
```