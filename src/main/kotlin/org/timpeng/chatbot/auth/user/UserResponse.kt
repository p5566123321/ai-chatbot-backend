package org.timpeng.chatbot.auth.user

import java.time.LocalDateTime

data class UserResponse(
    val id: Long,
    val email: String,
    val createdAt: LocalDateTime,
    val messageEmbeddingEnabled: Boolean,
) {
    companion object {
        fun from(user: User): UserResponse =
            UserResponse(
                id = user.id!!,
                email = user.email,
                createdAt = user.createdAt,
                messageEmbeddingEnabled = user.messageEmbeddingEnabled,
            )
    }
}
