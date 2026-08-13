package org.timpeng.chatbot.auth.user

import java.time.LocalDateTime

data class UserResponse(
    val id: Long,
    val email: String,
    val createdAt: LocalDateTime,
)
