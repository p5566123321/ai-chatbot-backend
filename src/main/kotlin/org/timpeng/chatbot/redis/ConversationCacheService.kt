package org.timpeng.chatbot.redis

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.message.Message
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Service
class ConversationCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    @Value($$"${app.conversation.cache.max-msg}") private val maxMessages: Long = 10,
    @Value($$"${app.conversation.cache.ttl-min}") private val ttlMinutes: Long = 30L,
) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private fun historyKey(conversationId: String) = "chat:conversation:$conversationId"

    fun getChatHistory(conversationId: String): List<Message> {
        val redisKey = historyKey(conversationId)

        // LRANGE key 0 -1
        val jsonList = redisTemplate.opsForList().range(redisKey, 0, -1) ?: emptyList()

        logger.info("[REDIS] get chat history: conversationId=$conversationId, length=${jsonList.size} ")

        return jsonList.map { objectMapper.readValue(it, Message::class.java) }
    }

    fun saveChatMessage(conversationId: String, message: Message) {

        val redisKey = historyKey(conversationId)
        val jsonMessage = objectMapper.writeValueAsString(message)
        // Per-user override (users.history_max_messages, V10) falls back to the global default —
        // same resolution as DatabaseConversationHistoryService, so a cache-populated read and a
        // DB-fallback read agree on how many messages "belongs" to this caller.
        val effectiveMax = message.conversation.ownerId
            ?.let { userRepository.findById(it).map { user -> user.historyMaxMessages?.toLong() }.orElse(null) }
            ?: maxMessages

        redisTemplate.executePipelined { connection ->
            redisTemplate.opsForList().rightPush(redisKey, jsonMessage)

            redisTemplate.opsForList().trim(redisKey, -effectiveMax, -1)

            redisTemplate.expire(redisKey, Duration.ofMinutes(ttlMinutes))

            null
        }

    }

    fun saveChatMessageList(conversationId: String, messages: List<Message>) {
        for (message in messages) {
            saveChatMessage(conversationId, message)
        }
    }
}