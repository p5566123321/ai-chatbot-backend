package org.timpeng.chatbot.conversation.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider

/**
 * Embeds chat messages into `messages.embedding` (V5__add_message_embedding_column.sql), gated
 * by a **per-user** switch (`users.message_embedding_enabled`, V6, toggled via
 * `PATCH /api/users/me/message-embedding` — see [org.timpeng.chatbot.auth.user.UserController]),
 * not a static app.* config value: this is a UI-controlled setting, so it's read per message from
 * the conversation's owner. Called from
 * [org.timpeng.chatbot.conversation.ConversationService.saveMessage] after commit — same trigger
 * point as the Redis cache write — for every USER/ASSISTANT message saved through the existing
 * chat endpoints, sync or streaming.
 *
 * Unlike the Redis cache write, embedding isn't done inline: a Gemini embed call is a real
 * external round-trip (see [org.timpeng.chatbot.rag.embedding.GeminiEmbeddingProvider]'s
 * timeout), and every message save is on the hot chat path — `ChatService.chat`'s response and
 * `ChatJobHandler`'s SSE stream must not wait on it. So once the switch check passes, [embedAsync]
 * fires the actual embed+persist on its own [CoroutineScope] and returns immediately; failures
 * there are logged and swallowed rather than propagated, since there is no caller left by the
 * time the embed finishes to hand an error to. Reuses the same [EmbeddingProvider] (model/
 * dimensions) already used for document chunks — no separate provider or config needed.
 */
@Service
class MessageEmbeddingService(
    private val embeddingProvider: EmbeddingProvider,
    private val messageEmbeddingRepository: MessageEmbeddingJdbcRepository,
    private val userRepository: UserRepository,
) {
    private val logger = LoggerFactory.getLogger(MessageEmbeddingService::class.java)

    // SupervisorJob so one failed embed can't cancel sibling in-flight embeds sharing this scope;
    // Dispatchers.IO since embedding is blocked on network I/O (see GeminiEmbeddingProvider).
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun embedAsync(message: Message) {
        // Pre-auth conversations (ownerId == null, ADR-007) have no user to read the switch from
        // — nothing to embed on behalf of. This lookup runs synchronously (indexed PK read) on
        // the caller's thread rather than inside the background scope below, so the expensive
        // embed API call is only ever launched once we know it's actually wanted.
        val ownerId = message.conversation.ownerId ?: return
        val enabled = userRepository.findById(ownerId)
            .map { it.messageEmbeddingEnabled }
            .orElse(false)
        if (!enabled) return

        scope.launch {
            try {
                val vector = embeddingProvider.embed(message.content)
                messageEmbeddingRepository.updateEmbedding(message.id, vector)
            } catch (e: Exception) {
                logger.error("Failed to embed message id=${message.id}", e)
            }
        }
    }
}
