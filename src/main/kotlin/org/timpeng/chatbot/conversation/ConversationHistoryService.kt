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
) {

    @Value($$"${app.conversation.cache.max-msg}")
    private val maxMessages = 10

    private val logger = LoggerFactory.getLogger(ConversationHistoryService::class.java)

    fun getHistory(conversationId : String): List<Message> {
        val cacheSample = Timer.start(meterRegistry)
        var messages = redisService.getChatHistory(conversationId)
        val cacheHit = messages.isNotEmpty()
        val durationNanos = cacheSample.stop(
            Timer.builder("history.cache.time")
                .description("讀取聊天記錄快取耗時")
                .tag("result", if (cacheHit) "hit" else "miss")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        )
        val durationSec: Double = durationNanos / 1000000000.0
        logger.info("[Redis] historyDelay=${durationSec}Sec")

        if(!cacheHit){
            val dbSample = Timer.start(meterRegistry)
            val conversation = conversationRepository.findByUuid(conversationId)
                .orElseGet {
                    logger.info("[GetHistory] no conversation with id=$conversationId, create new conversation.")
                    conversationRepository.save(
                        Conversation(
                            uuid = conversationId
                        )
                    )
                }
            val pageable: Pageable = PageRequest.of(0, maxMessages)

            messages = messageRepository.findByConversationOrderByCreatedAt(conversation, pageable)
            val durationNanos = dbSample.stop(
                Timer.builder("history.db.fallback.time")
                    .description("快取未命中時查詢資料庫耗時")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )

            val durationSec: Double = durationNanos / 1000000000.0
            logger.info("[DB] historyDelay=${durationSec}Sec")


            redisService.saveChatMessageList(conversationId, messages)

        }

        logger.info("[History] conversationId=$conversationId length=${messages.size} ")
        return messages
    }
}