package org.timpeng.chatbot.redis

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

// A generating-status key marks "this conversation currently has a stream in flight" so a
// client that disconnects and reconnects (or polls from another tab) can check progress
// without holding the original SSE connection open. TTL is a safety net matching the SSE
// timeout so a crashed/killed backend doesn't leave a conversation stuck reporting "generating".
@Service
class GeneratingStatusService(
    private val redisTemplate: StringRedisTemplate,
    @Value($$"${app.sse.timeout-ms}") private val sseTimeoutMs: Long = 60000L,
) {


    private fun generatingKey(conversationId: String) = "chat:generating:$conversationId"

    fun markGenerating(conversationId: String) {
        val ttl = Duration.ofMillis(sseTimeoutMs).plusSeconds(10)
        redisTemplate.opsForValue().set(generatingKey(conversationId), "", ttl)
    }

    fun updateGeneratingProgress(conversationId: String, partial: String) {
        val ttl = Duration.ofMillis(sseTimeoutMs).plusSeconds(10)
        redisTemplate.opsForValue().set(generatingKey(conversationId), partial, ttl)
    }

    fun clearGenerating(conversationId: String) {
        redisTemplate.delete(generatingKey(conversationId))
    }

    // Returns the partial text generated so far if a stream is in flight, or null if not.
    fun getGeneratingProgress(conversationId: String): String? {
        return redisTemplate.opsForValue().get(generatingKey(conversationId))
    }

}