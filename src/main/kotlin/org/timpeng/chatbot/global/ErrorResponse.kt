package org.timpeng.chatbot.global

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)