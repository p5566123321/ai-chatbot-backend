package org.timpeng.chatbot.conversation

import java.time.LocalDateTime

data class ConversationResponse(
    val uuid: String,
    val createdAt: LocalDateTime
)
