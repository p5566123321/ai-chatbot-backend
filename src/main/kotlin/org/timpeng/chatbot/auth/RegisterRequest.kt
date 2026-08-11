package org.timpeng.chatbot.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotBlank(message = "email must not be blank")
    @field:Email(message = "email must be a valid email address")
    @field:Size(max = 255, message = "email must be at most 255 characters")
    val email: String,

    // 72 bytes is BCrypt's own input cap (BCryptPasswordEncoder throws past it) — bounding it
    // here gives a clear 400 instead of letting that surface as an opaque encoder error.
    @field:NotBlank(message = "password must not be blank")
    @field:Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
    val password: String,
)
