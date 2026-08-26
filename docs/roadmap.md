# Roadmap

## Phase 1
- REST API
- LLM integration
- PostgreSQL

## Phase 2
- Streaming
- Redis context

## Phase 3
- Queue system

## Phase 4
- RAG

## Phase 5
- JWT authentication + per-user conversation ownership (see ADR-007, `docs/architecture.md`)

## Phase 6
- User-configurable model parameters: model choice, bring-your-own API key (BYOK), history
  window size
- Generation-parameter overrides (temperature/topP/topK/candidateCount/maxOutputTokens/
  systemInstruction) shipped first, via `V7__add_user_gemini_settings.sql` and
  `PATCH /api/users/me/gemini-settings` — see `CLAUDE.md`'s "LLM provider abstraction" section.
- **Done**: chat model choice (fixed whitelist, `GeminiSettings.model`) and BYOK (per-user Gemini
  API key, AES-256-GCM at rest) — see ADR-010. Deliberately scoped to the chat path only; the
  embedding model/embedding BYOK is called out there as follow-up work, blocked on the shared
  `vector(768)` column and `EmbeddingProvider.embed` not carrying an `ownerId`.
- **Done**: history window size — `users.history_max_messages` (V10, nullable, null = use the
  global `app.conversation.cache.max-msg` default) via `PATCH /api/users/me/history-max-messages`,
  resolved independently by `DatabaseConversationHistoryService`/`ConversationCacheService` so DB
  and cache reads agree. Phase 6 is now fully done.