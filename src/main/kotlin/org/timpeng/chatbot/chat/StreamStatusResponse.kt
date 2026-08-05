package org.timpeng.chatbot.chat

data class StreamStatusResponse(
    val generating: Boolean,
    val partial: String? = null
)
