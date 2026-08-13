package org.timpeng.chatbot.conversation

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.exception.ConversationNotFoundException

/**
 * Loads conversation history straight from Postgres — the source of truth. This is the only
 * [ConversationHistoryService] bean when `app.conversation.cache.enabled=false`, and is also used
 * directly (not through the interface) as the fallback delegate by
 * [CachedConversationHistoryService] on a Redis cache miss.
 */
@Service
class DatabaseConversationHistoryService(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val meterRegistry: MeterRegistry,
    @Value($$"${app.conversation.cache.max-msg}") private val maxMessages: Int = 10,
) : ConversationHistoryService {

    private val logger = LoggerFactory.getLogger(DatabaseConversationHistoryService::class.java)

    override fun getHistory(conversationId: String): List<Message> {
        val sample = Timer.start(meterRegistry)

        val conversation = conversationRepository.findByUuid(conversationId)
            .orElseThrow { ConversationNotFoundException("Conversation not found: $conversationId") }
        val pageable: Pageable = PageRequest.of(0, maxMessages)
        val messages = messageRepository.findByConversationOrderByCreatedAt(conversation, pageable)

        val durationSec = sample.stop(
            Timer.builder("history.db.fallback.time")
                .description("查詢資料庫歷史訊息耗時")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        ) / 1_000_000_000.0

        logger.info("[DB] conversationId=$conversationId length=${messages.size} historyDelay=${durationSec}Sec")
        return messages
    }
}
