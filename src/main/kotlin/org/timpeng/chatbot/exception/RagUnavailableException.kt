package org.timpeng.chatbot.exception

// Thrown by DocumentService.upload/replace when no EmbeddingProvider bean exists — the deployment
// has no system-wide embedding key configured (app.llm.gemini.system-api-key / GOOGLE_API_KEY
// unset). Unlike MissingApiKeyException there's no per-user fix for this: RAG has no BYOK path at
// all (ADR-010's "Context" explains why — fixed vector(N) columns, EmbeddingProvider.embed doesn't
// carry an ownerId), so this means the feature is off for everyone on this deployment, not just
// this caller. Maps to 503, not 400.
class RagUnavailableException(message: String) : RuntimeException(message)
