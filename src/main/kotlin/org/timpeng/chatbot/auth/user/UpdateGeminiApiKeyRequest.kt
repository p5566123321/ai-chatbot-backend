package org.timpeng.chatbot.auth.user

import jakarta.validation.constraints.Size

/**
 * Body for `PATCH /api/users/me/gemini-api-key` (BYOK, docs/decision/010). `apiKey` null or blank
 * clears any stored key (reverting to the app-wide default `GOOGLE_API_KEY`); anything else is
 * encrypted (`ApiKeyCipher`) and stored as-is — this endpoint deliberately does not call Gemini to
 * validate the key works, so an invalid key only surfaces later as the existing
 * [org.timpeng.chatbot.exception.LlmException] 503 path on the caller's next chat request. The
 * bound below just catches obviously-malformed input; real Gemini API keys are much shorter.
 */
data class UpdateGeminiApiKeyRequest(
    @field:Size(max = 200, message = "apiKey must be at most 200 characters")
    val apiKey: String? = null,
)
