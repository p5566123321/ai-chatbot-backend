package org.timpeng.chatbot.llm

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.StreamCancelledException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeLlmProviderTest {

    private lateinit var provider: FakeLlmProvider
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry()

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")
    private val messages = listOf(Message(conversation = conversation, role = Role.USER, content = "Hi"))

    @BeforeEach
    fun setUp() {
        provider = FakeLlmProvider(meterRegistry)
    }

    @Test
    fun `generate returns a deterministic non-blank message and the fake model name`() {
        val result = provider.generate(messages)

        assertTrue(result.message.isNotBlank())
        assertEquals("fake", result.model)
    }

    @Test
    fun `generate never calls out externally - same input twice returns the same message`() {
        assertEquals(provider.generate(messages).message, provider.generate(messages).message)
    }

    @Test
    fun `generate records llm generate time tagged provider=fake`() {
        provider.generate(messages)

        val timer = meterRegistry.find("llm.generate.time").tag("provider", "fake").timer()

        assertEquals(1, timer?.count())
    }

    @Test
    fun `streamGenerate delivers more than one chunk`() {
        val chunks = mutableListOf<String>()

        provider.streamGenerate(messages) { chunks.add(it) }

        assertTrue(chunks.size > 1, "expected multiple chunks, got ${chunks.size}")
        assertTrue(chunks.joinToString("").isNotBlank())
    }

    @Test
    fun `streamGenerate propagates StreamCancelledException from onChunk instead of swallowing it`() {
        var seen = 0

        assertThrows<StreamCancelledException> {
            provider.streamGenerate(messages) {
                seen++
                throw StreamCancelledException()
            }
        }

        assertEquals(1, seen, "should stop at the first chunk once cancelled, not keep streaming")
    }

    @Test
    fun `streamGenerate tags the outcome cancelled when the caller cancels mid-stream`() {
        runCatching { provider.streamGenerate(messages) { throw StreamCancelledException() } }

        val timer = meterRegistry.find("llm.stream_generate.time").tag("outcome", "cancelled").timer()

        assertEquals(1, timer?.count())
    }

    @Test
    fun `streamGenerate records first token latency`() {
        provider.streamGenerate(messages) { }

        val timer = meterRegistry.find("llm.stream_generate.first_token_time").tag("provider", "fake").timer()

        assertEquals(1, timer?.count())
    }
}
