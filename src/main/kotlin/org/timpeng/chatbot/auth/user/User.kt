package org.timpeng.chatbot.auth.user

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "users", indexes = [Index(name = "idx_user_email", columnList = "email", unique = true)])
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val email: String,

    // BCrypt hash only — never the plaintext password. See PasswordEncoderConfig.
    @Column(nullable = false)
    val passwordHash: String,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    // On/off switch for MessageEmbeddingService, toggled via PATCH /api/users/me/message-embedding
    // (see UserController) — not an app.* config value, since this is a per-user UI-controlled
    // setting (V6__add_user_message_embedding_flag.sql). Defaults false: opt-in, since it costs a
    // real embedding API call per message.
    @Column(nullable = false)
    val messageEmbeddingEnabled: Boolean = false,

    // Per-user GenerateContentConfig overrides, toggled via PATCH /api/users/me/gemini-settings
    // (see UserController/GeminiSettings) — read by GeminiProvider per request via ownerId.
    // Embeddable rather than 6 more flat columns, all independently nullable
    // (V7__add_user_gemini_settings.sql); an all-null GeminiSettings() default means every field
    // falls back to the Gemini API's own default, same as before this feature existed.
    @Embedded
    val geminiSettings: GeminiSettings = GeminiSettings(),

    // BYOK: Base64(iv || AES-256-GCM ciphertext+tag) via ApiKeyCipher, never plaintext. Set/cleared
    // via PATCH /api/users/me/gemini-api-key (UserController/UserService). Deliberately not part
    // of GeminiSettings — see V8's migration comment for why. Read by GeminiClientFactory to build
    // a per-user google-genai Client when present, falling back to the app-wide default otherwise.
    @Column(name = "gemini_api_key_ciphertext", columnDefinition = "TEXT")
    val geminiApiKeyCiphertext: String? = null,
)
