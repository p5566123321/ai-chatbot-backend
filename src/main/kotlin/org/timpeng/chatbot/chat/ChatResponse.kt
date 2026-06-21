package org.timpeng.chatbot.chat

data class ChatResponse (
    val message: String,
    val model: String,
    val latencyMs: Long
)