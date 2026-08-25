package org.timpeng.chatbot.auth.user

import jakarta.validation.Valid
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

    // Full replace of GeminiSettings, read by GeminiProvider per request via the message's
    // ownerId — see UpdateGeminiSettingsRequest's kdoc for why this isn't a partial merge.
    @PatchMapping("/gemini-settings")
    fun updateGeminiSettings(
        @CurrentUserId userId: Long,
        @Valid @RequestBody request: UpdateGeminiSettingsRequest,
    ): UserResponse =
        userService.updateGeminiSettings(userId, request)
}
