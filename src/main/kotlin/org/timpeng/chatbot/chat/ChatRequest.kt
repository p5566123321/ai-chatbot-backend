package org.timpeng.chatbot.chat

data class ChatRequest(
    val message: String,
    val userId: String? = null
)
