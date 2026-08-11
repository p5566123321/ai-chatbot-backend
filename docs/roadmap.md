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
- Deliberately sequenced after Phase 4 (RAG), not before: model choice will need to cover both
  the chat model and the embedding model once RAG lands, and BYOK key storage (at-rest
  encryption, key management, keeping keys out of logs/metrics) is its own security surface
  worth an ADR rather than a bolt-on
- History window size (moving `app.conversation.cache.max-msg` from a global config to a
  per-user/per-conversation value) has no such dependency and can be pulled forward opportunistically
  if useful before the rest of this phase