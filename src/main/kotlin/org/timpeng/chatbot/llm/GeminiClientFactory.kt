package org.timpeng.chatbot.llm

import com.google.genai.Client
import com.google.genai.Models
import org.springframework.stereotype.Service
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.crypto.ApiKeyCipher
import org.timpeng.chatbot.exception.MissingApiKeyException

/**
 * Resolves the `Models` a Gemini call should go through for a given caller — the app-wide default
 * (`GenAIConfig`'s singleton, backed by `app.llm.gemini.system-api-key`/`GOOGLE_API_KEY`, optional)
 * unless the user has a BYOK key set (`User.geminiApiKeyCiphertext`, docs/decision/010), in which
 * case a fresh per-call `Client` is built with their decrypted key instead. A caller with neither
 * gets [MissingApiKeyException] — there is deliberately no silent degradation to "no LLM calls
 * work"; the caller needs a clear, actionable "set your own key" error instead.
 *
 * Deliberately builds a new `Client` per call rather than caching one per user: BYOK is not a hot
 * path, and not holding a decrypted plaintext key in memory any longer than one request is worth
 * more than the (cheap) cost of `Client.builder().build()`.
 */
@Service
class GeminiClientFactory(
    private val defaultModels: Models?,
    private val apiKeyCipher: ApiKeyCipher,
) {
    fun modelsFor(user: User?): Models {
        val ciphertext = user?.geminiApiKeyCiphertext
        if (ciphertext != null) {
            val apiKey = apiKeyCipher.decrypt(ciphertext)
            return Client.builder().apiKey(apiKey).build().models
        }
        return defaultModels ?: throw MissingApiKeyException(
            "No Gemini API key configured for this account. Set your own key via " +
                "PATCH /api/users/me/gemini-api-key."
        )
    }
}
