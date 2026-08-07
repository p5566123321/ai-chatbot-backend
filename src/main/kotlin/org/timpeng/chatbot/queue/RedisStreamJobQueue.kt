package org.timpeng.chatbot.queue

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

internal const val PAYLOAD_FIELD = "payload"

/**
 * Redis Streams-backed [JobQueue]. One instance = one stream key = one logical queue, mirroring
 * how a Kafka topic or RabbitMQ queue each map 1:1 to a single unit of work. Pairs with
 * [RedisStreamConsumer] on the read side. See
 * docs/decision/006-queue-technology-selection.md for why Streams (not a plain Redis List) was
 * chosen and what is/isn't portable if this is ever swapped for a real broker.
 */
class RedisStreamJobQueue<T : Any>(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val streamKey: String,
) : JobQueue<T> {

    override fun enqueue(payload: T): String {
        val sample = Timer.start(meterRegistry)
        val json = objectMapper.writeValueAsString(payload)
        val recordId = redisTemplate.opsForStream<String, String>()
            .add(StreamRecords.string(mapOf(PAYLOAD_FIELD to json)).withStreamKey(streamKey))
        sample.stop(
            Timer.builder("queue.enqueue.time")
                .description("排入 queue 耗時")
                .tag("stream", streamKey)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        )
        return recordId.value
    }
}
