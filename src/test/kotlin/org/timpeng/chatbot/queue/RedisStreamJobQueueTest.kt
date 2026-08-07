package org.timpeng.chatbot.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

// Shared with RedisStreamConsumerIntegrationTest.kt (same package).
internal data class TestJobPayload(val text: String)

class RedisStreamJobQueueTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val streamOps: StreamOperations<String, String, String> = mockk()
    private val objectMapper = ObjectMapper()
    private lateinit var queue: RedisStreamJobQueue<TestJobPayload>

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForStream<String, String>() } returns streamOps
        queue = RedisStreamJobQueue(redisTemplate, objectMapper, SimpleMeterRegistry(), "test-stream")
    }

    @Test
    fun `enqueue serializes the payload and XADDs it to the configured stream, returning the record id`() {
        val recordSlot = slot<MapRecord<String, String, String>>()
        every { streamOps.add(capture(recordSlot)) } returns RecordId.of("1-1")

        val id = queue.enqueue(TestJobPayload("hello"))

        assertEquals("1-1", id)
        val captured = recordSlot.captured
        assertEquals("test-stream", captured.stream)
        assertEquals("""{"text":"hello"}""", captured.value["payload"])
    }
}
