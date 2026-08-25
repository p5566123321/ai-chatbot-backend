package org.timpeng.chatbot.auth.user

import org.springframework.stereotype.Service

/**
 * Backs `GET /api/users/me` and `PATCH /api/users/me/message-embedding` (see [UserController]) —
 * account-level reads/writes, separate from [org.timpeng.chatbot.auth.AuthService] which only
 * covers register/login.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
) {
    fun getUser(userId: Long): UserResponse =
        UserResponse.from(requireUser(userId))

    // A valid @CurrentUserId means JwtAuthenticationFilter trusted a token for this id — the row
    // disappearing after that (never happens today, no delete-account endpoint exists) would be a
    // broken invariant, not a normal 404, so this deliberately isn't a domain NotFoundException.
    private fun requireUser(userId: Long): User =
        userRepository.findById(userId).orElseThrow {
            IllegalStateException("Authenticated userId=$userId has no User row")
        }

    fun setMessageEmbeddingEnabled(userId: Long, enabled: Boolean): UserResponse {
        val user = requireUser(userId)
        val updated = userRepository.save(user.copy(messageEmbeddingEnabled = enabled))
        return UserResponse.from(updated)
    }
}
