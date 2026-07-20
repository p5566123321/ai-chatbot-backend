package org.timpeng.chatbot.redis

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Service
class RedisService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Value("\${app.conversation.cache.max-msg}")
    private val maxMessages = 10L
    @Value("\${app.conversation.cache.ttl-min}")
    private val ttlMinutes = 30L

    fun getChatHistory(conversationId: String): List<Message> {
        logger.info("[REDIS] get chat history: conversationId=$conversationId ")
        val redisKey = "chat:conversation:$conversationId"

        // LRANGE key 0 -1
        val jsonList = redisTemplate.opsForList().range(redisKey, 0, -1) ?: emptyList()

        return jsonList.map { objectMapper.readValue(it, Message::class.java) }
    }

    fun saveChatMessage(conversationId: String, message: Message) {
        logger.info("[GetHistory] no conversation with id=$conversationId, create new conversation.")

        val redisKey = "chat:conversation:$conversationId"
        val jsonMessage = objectMapper.writeValueAsString(message)

        // 使用 Redis Pipeline 或 Execute 確保操作連續執行
        redisTemplate.executePipelined { connection ->
            // 1. RPUSH
            redisTemplate.opsForList().rightPush(redisKey, jsonMessage)

            // 2. LTRIM (只保留最近 maxMessages 則)
            redisTemplate.opsForList().trim(redisKey, -maxMessages, -1)

            // 3. EXPIRE (延長 30 分鐘)
            redisTemplate.expire(redisKey, Duration.ofMinutes(ttlMinutes))

            null // executePipelined 需要回傳 null
        }
    }

}