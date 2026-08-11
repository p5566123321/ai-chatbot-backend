package org.timpeng.chatbot.auth

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    // Deliberately just NotBlank here, not the format/length rules RegisterRequest enforces:
    // login checks credentials against whatever's already stored, so tightening those rules
    // later must never lock an existing account out of its own login.
    @field:NotBlank(message = "email must not be blank")
    val email: String,

    @field:NotBlank(message = "password must not be blank")
    val password: String,
)
