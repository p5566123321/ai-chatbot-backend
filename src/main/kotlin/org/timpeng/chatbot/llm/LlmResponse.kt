package org.timpeng.chatbot.llm

data class LlmResponse (
    val message: String,
    val model: String,
    val latencyMs: Long
)