package org.timpeng.chatbot.auth.user

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * Body for `PATCH /api/users/me/history-max-messages`. `maxMessages` null clears the override,
 * reverting to the global `app.conversation.cache.max-msg` default — same convention as
 * [GeminiSettings]' fields, not a partial-merge endpoint.
 *
 * Bounds are a sanity guard, not a tuned limit: too small loses useful context, too large risks a
 * single request pulling an unreasonably large `PageRequest`/Redis list.
 */
data class UpdateHistoryMaxMessagesRequest(
    @field:Min(value = 1, message = "maxMessages must be at least 1")
    @field:Max(value = 100, message = "maxMessages must be at most 100")
    val maxMessages: Int? = null,
)
