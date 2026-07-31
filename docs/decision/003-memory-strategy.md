# ADR-003: Memory Strategy

**Status:** Accepted

## Context

For a better user experience, conversation history needs to be available to the LLM on every request without hitting the database every time. We introduced Redis as a short-term memory cache. The question is how to combine it with the existing Postgres long-term storage (`Conversation` / `Message` tables) without doubling write complexity or serving the LLM stale/incomplete history.

## Decision

**Redis-first read, with fallback to the database on a cache miss.**

On each incoming message:

1. Look up recent history in Redis under `chat:session:{conversationId}`.
2. **Cache hit** — the user has been active within the TTL window. Send the cached history straight to the LLM.
3. **Cache miss** — the session is new or expired. Query Postgres for the last ~10 messages instead.
4. **Warm-up** — on a cache miss, write the messages fetched from Postgres back into Redis so the next request in the same session hits the cache.

Every new message is written to both stores: appended to Redis (`RPUSH`, trimmed to the last N messages, TTL refreshed) and persisted to Postgres for durability.

## Options considered

### Option A — Always combine long-term history, write to DB only when short-term memory expires

| | |
|---|---|
| ✅ | LLM always receives the complete conversation history |
| ❌ | Every request pays a DB round-trip, adding latency |
| ❌ | Significant complexity in merging/deduping short-term and long-term data |

### Option B — Double-write to both stores, read from short-term first

| | |
|---|---|
| ✅ | Low latency and good user experience on the hot path |
| ✅ | Simple to reason about and operate — Redis is a pure cache, Postgres is the source of truth |
| ❌ | On a cache miss, the LLM only sees the last ~10 messages, not the full history |

## Rationale

We chose **Option B**. The latency cost of a DB round-trip on every message (Option A) isn't justified for a chatbot where recent context matters far more than full history on every turn. Capping context at ~10 messages on a cache miss is an acceptable trade-off:

- Redis remains a disposable cache — Postgres is always the source of truth, so there's no data-consistency risk if Redis is flushed or unavailable.
- The 30-minute TTL matches typical session activity; a returning user outside that window is treated as starting a fresh short context rather than resuming mid-conversation.
- The warm-up step means only the *first* message after a cache miss pays the DB cost — subsequent messages in the same session are served from Redis again.

## Future considerations

| Capability | Description |
|---|---|
| Summary memory | When a conversation grows large, summarize the oldest N messages via an LLM call and store the summary under a separate Redis key, instead of dropping them entirely on trim. |
| Embedding / RAG | Embed messages into a vector DB to retrieve relevant older context on demand, rather than relying solely on recency. |
