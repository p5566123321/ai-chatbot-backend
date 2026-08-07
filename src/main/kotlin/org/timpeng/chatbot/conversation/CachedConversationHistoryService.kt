package org.timpeng.chatbot.conversation

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.redis.ConversationCacheService

/**
 * Redis cache-aside decorator over [DatabaseConversationHistoryService] (ADR-003): tries Redis
 * first, and on a miss falls back to the database delegate, then backfills Redis so the next read
 * in the same session hits the cache.
 *
 * Wired in place of the plain database reader whenever `app.conversation.cache.enabled` is true
 * (the default). Set it to `false` to force every read through Postgres instead — this exists
 * specifically to A/B the Redis benefit under load, see docs/decision/005.
 */
@Service
@Primary
@ConditionalOnProperty(
    name = ["app.conversation.cache.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CachedConversationHistoryService(
    private val conversationCacheService: ConversationCacheService,
    private val delegate: DatabaseConversationHistoryService,
    private val meterRegistry: MeterRegistry,
) : ConversationHistoryService {

    private val logger = LoggerFactory.getLogger(CachedConversationHistoryService::class.java)

    override fun getHistory(conversationId: String): List<Message> {
        val sample = Timer.start(meterRegistry)
        val cached = conversationCacheService.getChatHistory(conversationId)
        val cacheHit = cached.isNotEmpty()

        val durationSec = sample.stop(
            Timer.builder("history.cache.time")
                .description("讀取聊天記錄快取耗時")
                .tag("result", if (cacheHit) "hit" else "miss")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        ) / 1_000_000_000.0
        logger.info("[Redis] historyDelay=${durationSec}Sec")

        if (cacheHit) {
            logger.info("[History] conversationId=$conversationId length=${cached.size}")
            return cached
        }

        val messages = delegate.getHistory(conversationId)
        conversationCacheService.saveChatMessageList(conversationId, messages)

        logger.info("[History] conversationId=$conversationId length=${messages.size}")
        return messages
    }
}
