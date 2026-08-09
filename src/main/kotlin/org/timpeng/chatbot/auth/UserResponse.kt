package org.timpeng.chatbot.auth

import java.time.LocalDateTime

data class UserResponse(
    val id: Long,
    val email: String,
    val createdAt: LocalDateTime,
)
