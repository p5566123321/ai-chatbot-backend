package org.timpeng.chatbot.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Per-user overrides for `GenerateContentConfig` (see `GeminiProvider.buildGenerationConfig`).
 * Every field is nullable and independently optional — null means "don't set this on the
 * request", letting the Gemini API fall back to its own default for that one parameter rather
 * than the whole config being all-or-nothing. Embedded directly on [User]
 * (V7__add_user_gemini_settings.sql) since these are account-wide, same as
 * [User.messageEmbeddingEnabled].
 */
@Embeddable
data class GeminiSettings(
    @Column(columnDefinition = "TEXT")
    val systemInstruction: String? = null,
    val temperature: Float? = null,
    // Explicit column names for topP/topK: Hibernate's default naming strategy only inserts an
    // underscore before an uppercase letter that's followed by another lowercase letter (so
    // "systemInstruction" -> "system_instruction" works fine), which means a trailing single
    // capital with nothing after it never gets split — "topP"/"topK" would otherwise validate
    // against nonexistent columns "topp"/"topk" instead of V7's "top_p"/"top_k".
    @Column(name = "top_p")
    val topP: Float? = null,
    @Column(name = "top_k")
    val topK: Float? = null,
    val candidateCount: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    fun isEmpty(): Boolean =
        systemInstruction == null && temperature == null && topP == null &&
            topK == null && candidateCount == null && maxOutputTokens == null
}
