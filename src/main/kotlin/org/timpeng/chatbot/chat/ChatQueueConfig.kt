package org.timpeng.chatbot.chat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.timpeng.chatbot.queue.JobQueue
import org.timpeng.chatbot.queue.QueueProperties
import org.timpeng.chatbot.queue.RedisStreamConsumer
import org.timpeng.chatbot.queue.RedisStreamJobQueue
import tools.jackson.databind.ObjectMapper

private const val CHAT_STREAM_KEY = "queue:chat"
private const val CHAT_GROUP_NAME = "chat-workers"

/**
 * Wires the chat job queue: one [RedisStreamJobQueue] producer bean (injected into
 * [ChatService]) and one [RedisStreamConsumer] that runs [ChatJobHandler]. This is the first
 * real use of `org.timpeng.chatbot.queue`'s abstraction — see
 * docs/decision/006-queue-technology-selection.md for the underlying design and
 * docs/architecture.md's Phase 3 section for where it fits.
 */
@Configuration(proxyBeanMethods = false)
class ChatQueueConfig {

    @Bean
    fun chatJobQueue(
        redisTemplate: StringRedisTemplate,
        objectMapper: ObjectMapper,
        meterRegistry: MeterRegistry,
    ): JobQueue<ChatJobPayload> =
        RedisStreamJobQueue(redisTemplate, objectMapper, meterRegistry, CHAT_STREAM_KEY)

    // initMethod = "start" kicks off the consumer's daemon polling thread once the bean (and
    // everything it depends on) is fully constructed. RedisStreamConsumer already implements
    // DisposableBean, so Spring calls destroy() on context shutdown without needing an explicit
    // destroyMethod here.
    @Bean(initMethod = "start")
    fun chatJobConsumer(
        redisTemplate: StringRedisTemplate,
        objectMapper: ObjectMapper,
        meterRegistry: MeterRegistry,
        queueProperties: QueueProperties,
        chatJobHandler: ChatJobHandler,
    ): RedisStreamConsumer<ChatJobPayload> =
        RedisStreamConsumer(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            streamKey = CHAT_STREAM_KEY,
            groupName = CHAT_GROUP_NAME,
            // Fixed name is fine while deployment is single-instance (see ADR-006's "known
            // limitations") — a second consumer process in the same group would need a name
            // unique per instance instead of this constant.
            consumerName = "chat-worker-1",
            payloadType = ChatJobPayload::class.java,
            handler = chatJobHandler,
            maxAttempts = queueProperties.maxAttempts,
            blockTimeoutMs = queueProperties.blockTimeoutMs,
            reclaimIdleMs = queueProperties.reclaimIdleMs,
        )
}
