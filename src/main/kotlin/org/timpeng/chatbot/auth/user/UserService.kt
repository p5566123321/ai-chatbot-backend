package org.timpeng.chatbot.auth.user

import org.springframework.stereotype.Service
import org.timpeng.chatbot.crypto.ApiKeyCipher
import org.timpeng.chatbot.llm.AllowedGeminiModels

/**
 * Backs `GET /api/users/me`, `PATCH /api/users/me/message-embedding`,
 * `PATCH /api/users/me/gemini-settings`, `GET /api/users/me/gemini-models`, and
 * `PATCH /api/users/me/gemini-api-key` (see [UserController]) — account-level reads/writes,
 * separate from [org.timpeng.chatbot.auth.AuthService] which only covers register/login.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val apiKeyCipher: ApiKeyCipher,
) {
    fun getUser(userId: Long): UserResponse =
        UserResponse.from(requireUser(userId))

    // A valid @CurrentUserId means JwtAuthenticationFilter trusted a token for this id — the row
    // disappearing after that (never happens today, no delete-account endpoint exists) would be a
    // broken invariant, not a normal 404, so this deliberately isn't a domain NotFoundException.
    //
    // Normalizes geminiSettings before returning: Hibernate hands back a literal `null` for an
    // @Embedded value when every column it maps to is NULL in the row (true for any user who
    // never called PATCH /gemini-settings), even though User.geminiSettings is a non-nullable
    // Kotlin type with a GeminiSettings() default — that default only runs when *constructing* a
    // User in application code, not when Hibernate populates one from a ResultSet. Left
    // unguarded, every caller below that does `user.copy(...)` without touching geminiSettings
    // crashes with a genuine NullPointerException from User.<init>'s own null-check on that
    // parameter (confirmed against real Postgres in UserGeminiSettingsLoadTest). `?:` here is
    // deliberately not "defensive over-caution" — Kotlin's elvis operator performs a real runtime
    // null check regardless of the operand's declared nullability, so this genuinely catches it.
    private fun requireUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow {
            IllegalStateException("Authenticated userId=$userId has no User row")
        }
        return user.copy(geminiSettings = user.geminiSettings ?: GeminiSettings())
    }

    fun setMessageEmbeddingEnabled(userId: Long, enabled: Boolean): UserResponse {
        val user = requireUser(userId)
        val updated = userRepository.save(user.copy(messageEmbeddingEnabled = enabled))
        return UserResponse.from(updated)
    }

    // Full replace, not a merge — see UpdateGeminiSettingsRequest's kdoc.
    fun updateGeminiSettings(userId: Long, request: UpdateGeminiSettingsRequest): UserResponse {
        request.model?.let {
            require(it in AllowedGeminiModels.IDS) {
                "model must be one of: ${AllowedGeminiModels.IDS}"
            }
        }
        val user = requireUser(userId)
        val updated = userRepository.save(user.copy(geminiSettings = request.toSettings()))
        return UserResponse.from(updated)
    }

    // BYOK (docs/decision/010) — blank/null clears the stored key, anything else is encrypted at
    // rest via ApiKeyCipher. See UpdateGeminiApiKeyRequest's kdoc for why this doesn't validate the
    // key against Gemini before storing it.
    fun updateGeminiApiKey(userId: Long, request: UpdateGeminiApiKeyRequest): UserResponse {
        val user = requireUser(userId)
        val ciphertext = request.apiKey?.takeIf { it.isNotBlank() }?.let { apiKeyCipher.encrypt(it) }
        val updated = userRepository.save(user.copy(geminiApiKeyCiphertext = ciphertext))
        return UserResponse.from(updated)
    }

    // Opt out of RAG augmentation on chat queries — read per-request by RagService.buildPrompt via
    // ownerId, same "UI-controlled setting, not app.* config" pattern as messageEmbeddingEnabled.
    fun setRagEnabled(userId: Long, enabled: Boolean): UserResponse {
        val user = requireUser(userId)
        val updated = userRepository.save(user.copy(ragEnabled = enabled))
        return UserResponse.from(updated)
    }
}
