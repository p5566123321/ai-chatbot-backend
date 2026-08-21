package org.timpeng.chatbot.chat

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.ConversationHistoryService
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.queue.Job
import org.timpeng.chatbot.redis.GeneratingStatusService
import java.io.IOException
import kotlin.test.assertNull

// Covers what used to be ChatService.streamChat's CompletableFuture.runAsync block, now
// relocated to ChatJobHandler.handle - see docs/decision/006-queue-technology-selection.md.
// ChatServiceTest covers the producer side (enqueue, emitter registration) instead.
class ChatJobHandlerTest {

    private val conversationService: ConversationService = mockk()
    private val historyService: ConversationHistoryService = mockk()
    private val llmProvider: LlmProvider = mockk()
    private val generatingStatusService: GeneratingStatusService = mockk(relaxed = true)
    private val emitterRegistry = SseEmitterRegistry()
    private lateinit var handler: ChatJobHandler
    private val emitter: SseEmitter = mockk(relaxed = true)

    private val conversationId = "test-uuid"
    private val ownerId = 1L
    private val conversation = Conversation(id = 1L, uuid = conversationId)
    private val history = listOf(
        Message(id = 1L, conversation = conversation, role = Role.USER, content = "hello")
    )

    @BeforeEach
    fun setUp() {
        handler = ChatJobHandler(
            conversationService,
            historyService,
            llmProvider,
            generatingStatusService,
            emitterRegistry,
        )
        every { historyService.getHistory(conversationId) } returns history
        emitterRegistry.register(conversationId, emitter)
    }

    private fun assistantMessage(content: String) =
        Message(id = 3L, conversation = conversation, role = Role.ASSISTANT, content = content)

    private fun job() = Job(id = "1-1", payload = ChatJobPayload(conversationId, ownerId), attempt = 1)

    @Test
    fun `handle sends each chunk as an SSE event`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("this", "is", "test", "response").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test response")

        handler.handle(job())

        verify { emitter.complete() }
        verify(exactly = 4) { emitter.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `handle completes the emitter after all chunks sent`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("this", "is", "test", "response").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test response")

        handler.handle(job())

        verify { emitter.complete() }
    }

    @Test
    fun `handle sends an error event and completes gracefully when llmProvider throws`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } throws RuntimeException("LLM unavailable")

        handler.handle(job())

        // The client can already be told about the failure via the error event's payload, so a
        // successful send lets us close cleanly instead of aborting the connection.
        verify { emitter.complete() }
        verify {
            emitter.send(match<SseEmitter.SseEventBuilder> {
                it.build().any { d -> d.data == "LLM unavailable" }
            })
        }
        verify(exactly = 0) { emitter.completeWithError(any()) }
    }

    @Test
    fun `handle sends chunks in order`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("A", "B", "C").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("ABC")

        handler.handle(job())

        verifyOrder {
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "A" } })
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "B" } })
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "C" } })
            emitter.complete()
        }
    }

    @Test
    fun `handle completes with error if emitter throws IOException`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("A", "B", "C").forEach(onChunk)
        }
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws IOException("client disconnected")
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("A")

        handler.handle(job())

        verify { emitter.completeWithError(any<IOException>()) }
    }

    @Test
    fun `handle clears generating status after a successful finish`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("this", "is", "test").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test")

        handler.handle(job())

        verify { generatingStatusService.clearGenerating(conversationId) }
    }

    @Test
    fun `handle clears generating status even when llmProvider throws`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } throws RuntimeException("LLM unavailable")

        handler.handle(job())

        verify { generatingStatusService.clearGenerating(conversationId) }
    }

    @Test
    fun `handle removes the emitter from the registry once done`() {
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            listOf("A").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("A")

        handler.handle(job())

        assertNull(emitterRegistry.get(conversationId))
    }

    @Test
    fun `handle is a no-op that clears generating status when no emitter is registered`() {
        emitterRegistry.remove(conversationId)

        handler.handle(job())

        verify(exactly = 0) { llmProvider.streamGenerate(any(), any(), any()) }
        verify { generatingStatusService.clearGenerating(conversationId) }
    }

    @Test
    fun `handle does not stream once the handle is marked cancelled`() {
        val handle = emitterRegistry.get(conversationId)!!
        handle.cancelled.set(true)
        every { llmProvider.streamGenerate(history, ownerId, any()) } answers {
            val onChunk = thirdArg<(String) -> Unit>()
            onChunk("A") // first chunk should throw StreamCancelledException and stop here
        }

        handler.handle(job())

        verify(exactly = 0) { emitter.complete() }
        verify(exactly = 0) { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) }
    }
}
