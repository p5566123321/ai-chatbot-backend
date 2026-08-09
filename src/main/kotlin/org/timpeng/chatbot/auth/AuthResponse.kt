package org.timpeng.chatbot.auth

import java.time.Instant

data class AuthResponse(
    val token: String,
    val expiresAt: Instant,
)
