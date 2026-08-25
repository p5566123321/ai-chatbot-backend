package org.timpeng.chatbot.auth.user

import jakarta.persistence.Column
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
)
