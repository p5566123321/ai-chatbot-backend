package org.timpeng.chatbot.conversation

import org.timpeng.chatbot.conversation.message.Message

/**
 * Reads the conversation history used as LLM context and returned by the history endpoint.
 *
 * Two implementations exist, wired conditionally on `app.conversation.cache.enabled`
 * (see docs/decision/003-memory-strategy.md):
 *  - [DatabaseConversationHistoryService] always exists and reads straight from Postgres.
 *  - [CachedConversationHistoryService] wraps it with a Redis cache-aside read and is the one
 *    injected everywhere unless caching is explicitly disabled (see docs/decision/005 for why
 *    that's a deploy-time switch rather than a per-request branch).
 */
interface ConversationHistoryService {
    fun getHistory(conversationId: String): List<Message>
}
