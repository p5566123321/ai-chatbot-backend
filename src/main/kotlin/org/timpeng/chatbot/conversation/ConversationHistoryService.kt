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
import org.timpeng.chatbot.redis.RedisService


@Service
class ConversationHistoryService (
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val redisService: RedisService,
    private val meterRegistry: MeterRegistry,
    // Benchmark switch: set app.conversation.cache.enabled=false to force every request through
    // the database, so the same load-test traffic can be replayed with and without Redis to
    // produce a like-for-like latency comparison. See docs/decision for the benchmark methodology.
    @Value($$"${app.conversation.cache.enabled:true}") private val cacheEnabled: Boolean = true,
) {

    @Value($$"${app.conversation.cache.max-msg}")
    private val maxMessages = 10

    private val logger = LoggerFactory.getLogger(ConversationHistoryService::class.java)

    fun getHistory(conversationId : String): List<Message> {
        val cacheSample = Timer.start(meterRegistry)
        var messages = if (cacheEnabled) redisService.getChatHistory(conversationId) else emptyList()
        val cacheHit = messages.isNotEmpty()
        val durationNanos = cacheSample.stop(
            Timer.builder("history.cache.time")
                .description("讀取聊天記錄快取耗時")
                .tag("result", if (!cacheEnabled) "disabled" else if (cacheHit) "hit" else "miss")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        )
        val durationSec: Double = durationNanos / 1000000000.0
        // When cacheEnabled=false, redisService is never called — this duration is just the
        // cost of the branch itself, not a Redis round-trip. Label it so benchmark logs aren't
        // misread as "Redis" latency when the cache is actually turned off.
        logger.info("[${if (cacheEnabled) "Redis" else "Cache-disabled"}] historyDelay=${durationSec}Sec")

        if(!cacheHit){
            val dbSample = Timer.start(meterRegistry)
            val conversation = conversationRepository.findByUuid(conversationId)
                .orElseThrow { ConversationNotFoundException("Conversation not found: $conversationId") }
            val pageable: Pageable = PageRequest.of(0, maxMessages)

            messages = messageRepository.findByConversationOrderByCreatedAt(conversation, pageable)
            val durationNanos = dbSample.stop(
                Timer.builder("history.db.fallback.time")
                    .description("快取未命中時查詢資料庫耗時")
                    .tag("cacheEnabled", cacheEnabled.toString())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )

            val durationSec: Double = durationNanos / 1000000000.0
            logger.info("[DB] historyDelay=${durationSec}Sec")

            if (cacheEnabled) {
                redisService.saveChatMessageList(conversationId, messages)
            }
        }

        logger.info("[History] conversationId=$conversationId length=${messages.size} ")
        return messages
    }
}