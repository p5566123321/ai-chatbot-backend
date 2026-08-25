package org.timpeng.chatbot.auth.user

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * Body for `PATCH /api/users/me/gemini-settings` — a full replace of [GeminiSettings], not a
 * partial merge: every field here maps 1:1 onto one on [GeminiSettings], and a field left out of
 * the request body (or sent as `null`) clears that override rather than leaving the previous
 * value in place. Bounds below are deliberately loose (Gemini's own per-model limits, especially
 * for maxOutputTokens, vary and are enforced by the API itself, surfacing as a normal
 * [org.timpeng.chatbot.exception.LlmException] 503) — these just catch obviously malformed input
 * before it reaches Gemini.
 */
data class UpdateGeminiSettingsRequest(
    // Must be one of AllowedGeminiModels.IDS — checked in UserService.updateGeminiSettings rather
    // than declaratively here, since it needs a clean 400 message listing the allowed set.
    val model: String? = null,

    @field:Size(max = 10_000, message = "systemInstruction must be at most 10000 characters")
    val systemInstruction: String? = null,

    @field:DecimalMin(value = "0.0", message = "temperature must be at least 0.0")
    @field:DecimalMax(value = "2.0", message = "temperature must be at most 2.0")
    val temperature: Float? = null,

    @field:DecimalMin(value = "0.0", message = "topP must be at least 0.0")
    @field:DecimalMax(value = "1.0", message = "topP must be at most 1.0")
    val topP: Float? = null,

    @field:DecimalMin(value = "1.0", message = "topK must be at least 1")
    val topK: Float? = null,

    @field:Min(value = 1, message = "candidateCount must be at least 1")
    @field:Max(value = 8, message = "candidateCount must be at most 8")
    val candidateCount: Int? = null,

    @field:Min(value = 1, message = "maxOutputTokens must be at least 1")
    val maxOutputTokens: Int? = null,
) {
    fun toSettings(): GeminiSettings = GeminiSettings(
        model = model,
        systemInstruction = systemInstruction,
        temperature = temperature,
        topP = topP,
        topK = topK,
        candidateCount = candidateCount,
        maxOutputTokens = maxOutputTokens,
    )
}
