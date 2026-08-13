package org.timpeng.chatbot.exception

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)