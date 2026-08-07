package org.timpeng.chatbot.redis

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Service
class ConversationCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
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

        redisTemplate.executePipelined { connection ->
            redisTemplate.opsForList().rightPush(redisKey, jsonMessage)

            redisTemplate.opsForList().trim(redisKey, -maxMessages, -1)

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