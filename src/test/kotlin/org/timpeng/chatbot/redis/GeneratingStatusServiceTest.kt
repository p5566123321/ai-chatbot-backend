package org.timpeng.chatbot.redis

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneratingStatusServiceTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private lateinit var generatingStatusService: GeneratingStatusService

    private val conversationId = "test-uuid"
    private val key = "chat:generating:$conversationId"

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        generatingStatusService = GeneratingStatusService(redisTemplate)
    }

    @Test
    fun `markGenerating sets an empty placeholder with a TTL`() {
        every { valueOps.set(any(), any(), any<Duration>()) } just Runs

        generatingStatusService.markGenerating(conversationId)

        verify { valueOps.set(key, "", any<Duration>()) }
    }

    @Test
    fun `updateGeneratingProgress overwrites the partial text`() {
        every { valueOps.set(any(), any(), any<Duration>()) } just Runs

        generatingStatusService.updateGeneratingProgress(conversationId, "Hello wor")

        verify { valueOps.set(key, "Hello wor", any<Duration>()) }
    }

    @Test
    fun `clearGenerating deletes the key`() {
        every { redisTemplate.delete(any<String>()) } returns true

        generatingStatusService.clearGenerating(conversationId)

        verify { redisTemplate.delete(key) }
    }

    @Test
    fun `getGeneratingProgress returns the stored partial text`() {
        every { valueOps.get(key) } returns "partial"

        assertEquals("partial", generatingStatusService.getGeneratingProgress(conversationId))
    }

    @Test
    fun `getGeneratingProgress returns null when nothing is in flight`() {
        every { valueOps.get(key) } returns null

        assertNull(generatingStatusService.getGeneratingProgress(conversationId))
    }
}
