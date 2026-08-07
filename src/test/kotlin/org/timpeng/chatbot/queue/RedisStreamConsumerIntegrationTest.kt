package org.timpeng.chatbot.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// TestJobPayload lives in RedisStreamJobQueueTest.kt (same package), shared by both tests.

/**
 * Exercises RedisStreamJobQueue + RedisStreamConsumer against a real local Redis — the
 * XREADGROUP/XACK/XCLAIM semantics this relies on aren't meaningfully testable with mocks (that
 * would just be asserting mocks return what we told them to). Needs `redis` up
 * (`docker compose up -d redis`) and REDIS_HOST/PORT/PASSWORD/DB in the environment
 * (`source scripts/load-env.sh` first) — same precondition as ChatSseIntegrationTest's full
 * `@SpringBootTest`.
 */
class RedisStreamConsumerIntegrationTest {

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private val objectMapper = ObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var streamKey: String

    @BeforeEach
    fun setUp() {
        val host = System.getenv("REDIS_HOST")
        assumeTrue(host != null, "REDIS_HOST not set — run 'source scripts/load-env.sh' first")

        val config = RedisStandaloneConfiguration(host, System.getenv("REDIS_PORT")?.toInt() ?: 6379).apply {
            System.getenv("REDIS_PASSWORD")?.takeIf { it.isNotBlank() }?.let { password = RedisPassword.of(it) }
            database = System.getenv("REDIS_DB")?.toInt() ?: 0
        }
        connectionFactory = LettuceConnectionFactory(config).apply {
            afterPropertiesSet()
            start()
        }
        redisTemplate = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        // Unique stream per test so tests never collide on each other's data/consumer groups.
        streamKey = "test:queue:${UUID.randomUUID()}"
    }

    @AfterEach
    fun tearDown() {
        redisTemplate.delete(streamKey)
        redisTemplate.delete("$streamKey:dlq")
        connectionFactory.destroy()
    }

    @Test
    fun `enqueued job is consumed exactly once and acked`() {
        val queue = RedisStreamJobQueue<TestJobPayload>(redisTemplate, objectMapper, meterRegistry, streamKey)
        val received = CountDownLatch(1)
        val seen = mutableListOf<Job<TestJobPayload>>()
        val consumer = RedisStreamConsumer(
            redisTemplate, objectMapper, meterRegistry, streamKey,
            groupName = "test-group", consumerName = "test-consumer-1",
            payloadType = TestJobPayload::class.java,
            handler = JobHandler { job ->
                seen.add(job)
                received.countDown()
            },
            blockTimeoutMs = 500,
        )

        try {
            consumer.start()
            queue.enqueue(TestJobPayload("hello"))

            assertTrue(received.await(5, TimeUnit.SECONDS), "handler was not invoked in time")
            assertEquals("hello", seen.single().payload.text)
            assertEquals(1, seen.single().attempt)

            // Give the ack a moment to land, then confirm nothing is left pending.
            Thread.sleep(200)
            val pending = redisTemplate.opsForStream<String, String>().pending(streamKey, "test-group")
            assertEquals(0, pending.totalPendingMessages)
        } finally {
            consumer.destroy()
        }
    }

    @Test
    fun `a handler that always throws exhausts retries and lands the job in the DLQ`() {
        val queue = RedisStreamJobQueue<TestJobPayload>(redisTemplate, objectMapper, meterRegistry, streamKey)
        val handleCount = java.util.concurrent.atomic.AtomicInteger(0)
        val seenAttempts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val consumer = RedisStreamConsumer(
            redisTemplate, objectMapper, meterRegistry, streamKey,
            groupName = "test-group", consumerName = "test-consumer-1",
            payloadType = TestJobPayload::class.java,
            handler = JobHandler { job ->
                handleCount.incrementAndGet()
                seenAttempts.add(job.attempt)
                throw RuntimeException("boom")
            },
            maxAttempts = 2,
            blockTimeoutMs = 300,
            reclaimIdleMs = 200,
        )

        try {
            consumer.start()
            queue.enqueue(TestJobPayload("will fail"))

            // Poll the DLQ stream until the job (first attempt fails, gets reclaimed and retried
            // once more, then dead-lettered) shows up — bounded so a real regression fails fast
            // rather than hanging.
            val deadline = System.currentTimeMillis() + 10_000
            var dlqRecords = emptyList<org.springframework.data.redis.connection.stream.MapRecord<String, String, String>>()
            while (System.currentTimeMillis() < deadline && dlqRecords.isEmpty()) {
                dlqRecords = redisTemplate.opsForStream<String, String>()
                    .read(StreamOffset.create("$streamKey:dlq", ReadOffset.from("0")))
                if (dlqRecords.isEmpty()) Thread.sleep(200)
            }

            assertEquals(1, dlqRecords.size)
            val dlqEntry = dlqRecords.single().value
            assertEquals("""{"text":"will fail"}""", dlqEntry["payload"])
            assertEquals("2", dlqEntry["attempt"])
            assertEquals("boom", dlqEntry["reason"])

            // The real regression this guards against: attempt numbering must match the true
            // Redis delivery count exactly, or a job survives more (or fewer) real deliveries
            // than maxAttempts says it should.
            assertEquals(2, handleCount.get(), "handler should run exactly maxAttempts times")
            assertEquals(listOf(1, 2), seenAttempts.toList())
        } finally {
            consumer.destroy()
        }
    }
}
