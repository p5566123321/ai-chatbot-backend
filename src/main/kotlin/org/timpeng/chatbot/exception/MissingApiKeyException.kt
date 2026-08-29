package org.timpeng.chatbot.exception

// Thrown by GeminiClientFactory.modelsFor when the caller has no BYOK key (User.geminiApiKeyCiphertext)
// and there is no system-wide fallback (app.llm.gemini.system-api-key / GOOGLE_API_KEY unset) — see
// GenAIConfig's kdoc. Deliberately distinct from LlmException: this is a client-fixable configuration
// problem ("set your own key"), not an external service failure, so it maps to 400 rather than 503.
class MissingApiKeyException(message: String) : RuntimeException(message)
