package org.timpeng.chatbot.auth.user

import java.time.LocalDateTime

data class UserResponse(
    val id: Long,
    val email: String,
    val createdAt: LocalDateTime,
    val messageEmbeddingEnabled: Boolean,
    val geminiSettings: GeminiSettings,
    // Whether a BYOK key is stored — never the ciphertext or plaintext itself (docs/decision/010).
    val hasGeminiApiKey: Boolean = false,
) {
    companion object {
        fun from(user: User): UserResponse =
            UserResponse(
                id = user.id!!,
                email = user.email,
                createdAt = user.createdAt,
                messageEmbeddingEnabled = user.messageEmbeddingEnabled,
                // `?: GeminiSettings()` guards a real Hibernate/JPA quirk, not defensive
                // over-caution: when every column an @Embedded value maps to is NULL in the row
                // (true for any user who never called PATCH /gemini-settings), Hibernate
                // reconstructs the entity with that field set to a literal null — bypassing
                // User.geminiSettings' Kotlin default entirely, since that default only runs when
                // *constructing* a User in application code, not when Hibernate populates one from
                // a ResultSet. Confirmed against real Postgres in UserGeminiSettingsLoadTest.
                geminiSettings = user.geminiSettings ?: GeminiSettings(),
                hasGeminiApiKey = user.geminiApiKeyCiphertext != null,
            )
    }
}
