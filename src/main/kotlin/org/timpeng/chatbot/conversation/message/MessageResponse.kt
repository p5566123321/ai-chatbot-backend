package org.timpeng.chatbot.conversation.message

import java.time.LocalDateTime

data class MessageResponse(
    val role: Role,
    val content: String,
    val createdAt: LocalDateTime?
)
