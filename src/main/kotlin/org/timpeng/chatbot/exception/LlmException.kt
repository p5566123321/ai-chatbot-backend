package org.timpeng.chatbot.exception

class LlmException(message: String, cause: Throwable? = null): RuntimeException(message, cause)