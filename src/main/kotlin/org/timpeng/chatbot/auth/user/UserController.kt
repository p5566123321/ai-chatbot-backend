package org.timpeng.chatbot.auth.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.timpeng.chatbot.auth.CurrentUserId

// Under /api/users, not /api/auth (AuthController) — this is account-level self-service, not
// registration/login. Requires auth like everything else under /api/** (SecurityConfig).
@RestController
@RequestMapping("/api/users/me")
class UserController(private val userService: UserService) {

    @GetMapping
    fun getCurrentUser(@CurrentUserId userId: Long): UserResponse =
        userService.getUser(userId)

    // Drives the account-wide message-embedding switch in the frontend's AppHeader — the caller
    // toggles this once and it applies to every conversation they own (MessageEmbeddingService
    // reads it per-message via the conversation's ownerId).
    @PatchMapping("/message-embedding")
    fun updateMessageEmbedding(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateMessageEmbeddingRequest,
    ): UserResponse =
        userService.setMessageEmbeddingEnabled(userId, request.enabled)
}
