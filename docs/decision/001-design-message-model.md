# ADR-001: Message Data Model Design

**Status:** Accepted

## Context

The chatbot needs a persistent data model for conversations and messages. The model must support the current MVP (store and replay history per conversation) and remain extensible for future capabilities: streaming responses, context-window management, RAG retrieval, analytics, and observability.

## Decision

Use two separate JPA entities — `Conversation` and `Message` — with a `ManyToOne` relationship from `Message` to `Conversation`.

### Schema

```
conversations
  id         BIGINT  PK (auto-increment, internal)
  uuid       VARCHAR NOT NULL (client-facing identifier)
  created_at DATETIME NOT NULL

messages
  id              BIGINT  PK (auto-increment)
  conversation_id BIGINT  FK → conversations.id
  role            VARCHAR NOT NULL  (USER | ASSISTANT | SYSTEM)
  content         TEXT    NOT NULL
  created_at      DATETIME NOT NULL
```

### Role Enum

| Value       | LLM name | Purpose                                 |
|-------------|----------|-----------------------------------------|
| `USER`      | `user`   | Human turn                              |
| `ASSISTANT` | `model`  | LLM response (Gemini uses `"model"`)    |
| `SYSTEM`    | `system` | Reserved for system prompt injection    |

### Conversation structure

```
Conversation (uuid)
 ├── Message (role=USER,      content="Hello")
 ├── Message (role=ASSISTANT, content="Hi! How can I help?")
 ├── Message (role=USER,      content="Tell me about Kotlin")
 └── Message (role=ASSISTANT, content="Kotlin is a JVM language …")
```

## Rationale

- **Separate entities** allow querying history per conversation without loading unrelated data, and keep conversation metadata (timestamps, future: title, owner) decoupled from message content.
- **Auto-increment PK + separate UUID** lets the database own referential integrity while exposing a non-guessable, client-safe identifier externally (see ADR-002).
- **`content TEXT`** avoids `VARCHAR` length limits; LLM responses can be arbitrarily long.
- **`@CreationTimestamp` on messages** enables ordered replay and future analytics (response latency, message rate) without extra writes.
- **`SYSTEM` role pre-included** avoids a schema migration when system prompts or RAG context injection is added.

## Future considerations

| Capability           | What this model enables                                           |
|----------------------|-------------------------------------------------------------------|
| Streaming response   | Save partial ASSISTANT message, update on completion             |
| Context memory       | Load `messages` ordered by `created_at`, truncate to token limit |
| RAG                  | Inject SYSTEM messages into history before LLM call              |
| Analytics            | Query message counts, latency (`created_at` delta), role ratios  |
| Retry / observability| Add `status` or `error` columns to `Message` without restructure |