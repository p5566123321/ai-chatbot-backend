package org.timpeng.chatbot.queue

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DlqReaderTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val streamOps: StreamOperations<String, String, String> = mockk()
    private lateinit var reader: DlqReader

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForStream<String, String>() } returns streamOps
        reader = DlqReader(redisTemplate)
    }

    private fun record(id: String, fields: Map<String, String>): MapRecord<String, String, String> =
        MapRecord.create("queue:chat:dlq", fields).withId(RecordId.of(id))

    @Test
    fun `list reads from the dlq stream, not the main stream`() {
        every {
            streamOps.reverseRange("queue:chat:dlq", any<Range<String>>(), any<Limit>())
        } returns emptyList()

        reader.list("queue:chat", 50)

        io.mockk.verify { streamOps.reverseRange("queue:chat:dlq", any<Range<String>>(), any<Limit>()) }
    }

    @Test
    fun `list parses all fields written by RedisStreamConsumer#deadLetter`() {
        every { streamOps.reverseRange("queue:chat:dlq", any<Range<String>>(), any<Limit>()) } returns listOf(
            record(
                "1-1",
                mapOf(
                    PAYLOAD_FIELD to """{"conversationId":"abc"}""",
                    REASON_FIELD to "boom",
                    ATTEMPT_FIELD to "3",
                    FAILED_AT_FIELD to "2026-08-08T10:00:00Z",
                ),
            )
        )

        val entries = reader.list("queue:chat", 50)

        assertEquals(
            listOf(
                DlqEntry(
                    id = "1-1",
                    payload = """{"conversationId":"abc"}""",
                    reason = "boom",
                    attempt = 3,
                    failedAt = Instant.parse("2026-08-08T10:00:00Z"),
                )
            ),
            entries,
        )
    }

    @Test
    fun `list tolerates a malformed attempt or failedAt instead of failing the whole read`() {
        every { streamOps.reverseRange("queue:chat:dlq", any<Range<String>>(), any<Limit>()) } returns listOf(
            record(
                "1-1",
                mapOf(
                    PAYLOAD_FIELD to """{"conversationId":"abc"}""",
                    REASON_FIELD to "boom",
                    ATTEMPT_FIELD to "not-a-number",
                    FAILED_AT_FIELD to "not-an-instant",
                ),
            )
        )

        val entry = reader.list("queue:chat", 50).single()

        assertEquals("boom", entry.reason)
        assertNull(entry.attempt)
        assertNull(entry.failedAt)
    }

    @Test
    fun `count reads the dlq stream size, not the main stream`() {
        every { streamOps.size("queue:chat:dlq") } returns 7L

        assertEquals(7L, reader.count("queue:chat"))
    }

    @Test
    fun `count returns 0 rather than null when the dlq stream doesn't exist yet`() {
        every { streamOps.size("queue:chat:dlq") } returns null

        assertEquals(0L, reader.count("queue:chat"))
    }
}
