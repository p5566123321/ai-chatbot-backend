# Chat Flow

Sequence of a single `POST /chat` request, covering the Redis short-term
memory cache, the Postgres fallback, and the Gemini LLM call.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant ChatService
    participant ConversationService
    participant ConversationHistoryService
    participant Redis
    participant DB as Postgres
    participant Gemini as GeminiProvider

    Client->>ChatService: chat(conversationId, userMsg)

    ChatService->>ConversationService: saveUserMessage(conversationId, userMsg)
    ConversationService->>ConversationHistoryService: getHistory(conversationId)

    ConversationHistoryService->>Redis: getChatHistory(conversationId)
    alt cache hit
        Redis-->>ConversationHistoryService: recent messages
    else cache miss
        ConversationHistoryService->>DB: findByUuid / findByConversationOrderByCreatedAt
        DB-->>ConversationHistoryService: messages (create conversation if new)
        ConversationHistoryService->>Redis: saveChatMessageList(conversationId, messages)
    end
    ConversationHistoryService-->>ConversationService: history

    ConversationService->>DB: save user Message
    ConversationService-->>ConversationService: cacheAfterCommit (afterCommit -> Redis.saveChatMessage)
    ConversationService-->>ChatService: history + user message

    ChatService->>Gemini: generate(messages)
    Gemini-->>ChatService: LlmResponse(message, model, latencyMs)

    ChatService->>ConversationService: saveAssistantMessage(conversationId, llmResponse)
    ConversationService->>DB: save assistant Message
    ConversationService-->>ConversationService: cacheAfterCommit (afterCommit -> Redis.saveChatMessage)
    ConversationService-->>ChatService: ChatResponse

    ChatService-->>Client: ChatResponse
```

## Notes

- **Cache-aside read**: `ConversationHistoryService.getHistory` checks Redis
  first; on a miss it loads the most recent `app.conversation.cache.max-msg`
  messages from Postgres, creating the `Conversation` row if it doesn't exist
  yet, then backfills Redis so the next read is a hit.
- **Write-after-commit**: `ConversationService.saveMessage` persists to
  Postgres inside the `@Transactional` boundary, then registers a
  `TransactionSynchronization` so the Redis cache is only updated
  `afterCommit`. This avoids caching a message that gets rolled back.
- **Metrics**: `history.cache.time`, `history.db.fallback.time`, and
  `llm.generate.time` timers (tagged with cache hit/miss and Gemini
  outcome) are published via Micrometer/Prometheus for latency tracking.