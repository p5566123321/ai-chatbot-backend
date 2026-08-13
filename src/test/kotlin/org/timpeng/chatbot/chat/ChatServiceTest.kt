package org.timpeng.chatbot.chat

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.ChatException
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.llm.LlmResponse
import org.timpeng.chatbot.queue.JobQueue
import org.timpeng.chatbot.redis.GeneratingStatusService
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Covers ChatService.chat (unchanged) plus streamChat's producer-side responsibilities: 404
// before any async work, registering the emitter/cancellation flag, enqueueing the job, and
// wiring the SSE lifecycle callbacks. The actual streaming/LLM/persistence behavior that used to
// live in streamChat's async block now belongs to ChatJobHandler - see ChatJobHandlerTest.
class ChatServiceTest {

    private val conversationService: ConversationService = mockk()
    private val llmProvider: LlmProvider = mockk()
    private val generatingStatusService: GeneratingStatusService = mockk(relaxed = true)
    private val emitterRegistry = SseEmitterRegistry()
    private val chatJobQueue: JobQueue<ChatJobPayload> = mockk(relaxed = true)
    private lateinit var chatService: ChatService
    private val emitter: SseEmitter = mockk(relaxed = true)

    private val conversationId = "test-uuid"
    private val ownerId = 1L
    private val conversation = Conversation(id = 1L, uuid = conversationId, ownerId = ownerId)
    private val history = listOf(
        Message(id = 1L, conversation = conversation, role = Role.USER, content = "previous")
    )

    @BeforeEach
    fun setUp() {
        chatService = ChatService(
            conversationService,
            llmProvider,
            generatingStatusService,
            emitterRegistry,
            chatJobQueue,
        )
        // Ownership check passes by default; tests focus on what happens after it, same as
        // every other test in this class already assumes saveUserMessage's own lookup succeeds.
        every { conversationService.requireOwnedConversation(any(), any()) } returns conversation
    }

    private fun userMessage(content: String) =
        Message(id = 2L, conversation = conversation, role = Role.USER, content = content)

    @Test
    fun `chat returns response with llm message, model, and latency`() {
        val llmResponse = LlmResponse(message = "Hi there!", model = "gemini-2.0-flash", latencyMs = 123L)
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } returns llmResponse
        every { conversationService.saveAssistantMessage(conversationId, llmResponse) } returns
            ChatResponse("Hi there!", "gemini-2.0-flash", 123L)

        val result = chatService.chat(conversationId, ownerId, "Hello")

        assertEquals(ChatResponse("Hi there!", "gemini-2.0-flash", 123L), result)
    }

    @Test
    fun `chat saves user message before calling llm`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } returns llmResponse
        every { conversationService.saveAssistantMessage(conversationId, llmResponse) } returns
            ChatResponse("Hi!", "gemini-2.0-flash", 50L)

        chatService.chat(conversationId, ownerId, "Hello")

        verify { conversationService.saveUserMessage(conversationId, "Hello") }
    }

    @Test
    fun `chat saves assistant message after llm responds`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } returns llmResponse
        every { conversationService.saveAssistantMessage(conversationId, llmResponse) } returns
            ChatResponse("Hi!", "gemini-2.0-flash", 50L)

        chatService.chat(conversationId, ownerId, "Hello")

        verify { conversationService.saveAssistantMessage(conversationId, llmResponse) }
    }

    @Test
    fun `chat works for a conversation with no prior history`() {
        val newConversationId = "new-uuid"
        val newConversation = Conversation(id = 2L, uuid = newConversationId)
        val newUserMessage = Message(id = 4L, conversation = newConversation, role = Role.USER, content = "Hi")
        val llmResponse = LlmResponse(message = "Hello!", model = "gemini-2.0-flash", latencyMs = 80L)

        every { conversationService.saveUserMessage(newConversationId, "Hi") } returns listOf(newUserMessage)
        every { llmProvider.generate(listOf(newUserMessage)) } returns llmResponse
        every { conversationService.saveAssistantMessage(newConversationId, llmResponse) } returns
            ChatResponse("Hello!", "gemini-2.0-flash", 80L)

        val result = chatService.chat(newConversationId, ownerId, "Hi")

        assertEquals("Hello!", result.message)
        verify { conversationService.saveUserMessage(newConversationId, "Hi") }
    }

    @Test
    fun `chat throws ChatException when llm provider fails`() {
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } throws RuntimeException("LLM unavailable")

        assertThrows<ChatException> {
            chatService.chat(conversationId, ownerId, "Hello")
        }
    }

    @Test
    fun `chat does not save assistant message when llm provider fails`() {
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } throws RuntimeException("LLM unavailable")

        runCatching { chatService.chat(conversationId, ownerId, "Hello") }

        verify(exactly = 0) { conversationService.saveAssistantMessage(conversationId, any()) }
    }

    @Test
    fun `chat returns raw markdown in response without html conversion`() {
        val markdown = "**bold**"
        val llmResponse = LlmResponse(message = markdown, model = "gemini-2.0-flash", latencyMs = 10L)
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } returns llmResponse
        every { conversationService.saveAssistantMessage(conversationId, llmResponse) } returns
            ChatResponse(markdown, "gemini-2.0-flash", 10L)

        val result = chatService.chat(conversationId, ownerId, "Hello")

        verify { conversationService.saveAssistantMessage(conversationId, llmResponse) }
        assertEquals(markdown, result.message)
    }

    @Test
    fun `streamChat saves the user message synchronously before enqueueing`() {
        every { conversationService.saveUserMessage(conversationId, "hello") } returns
            history + userMessage("hello")

        chatService.streamChat(conversationId, ownerId, "hello", emitter)

        verifyOrder {
            conversationService.saveUserMessage(conversationId, "hello")
            chatJobQueue.enqueue(ChatJobPayload(conversationId))
        }
    }

    @Test
    fun `streamChat propagates ConversationNotFoundException before touching the queue`() {
        every { conversationService.saveUserMessage(conversationId, "hello") } throws
            RuntimeException("Conversation not found: $conversationId")

        assertThrows<RuntimeException> {
            chatService.streamChat(conversationId, ownerId, "hello", emitter)
        }

        verify(exactly = 0) { chatJobQueue.enqueue(any()) }
    }

    @Test
    fun `streamChat marks generating and enqueues a job carrying the conversationId`() {
        every { conversationService.saveUserMessage(conversationId, "hello") } returns
            history + userMessage("hello")

        chatService.streamChat(conversationId, ownerId, "hello", emitter)

        verify { generatingStatusService.markGenerating(conversationId) }
        verify { chatJobQueue.enqueue(ChatJobPayload(conversationId)) }
    }

    @Test
    fun `streamChat registers the emitter under the conversationId`() {
        every { conversationService.saveUserMessage(conversationId, "hello") } returns
            history + userMessage("hello")

        chatService.streamChat(conversationId, ownerId, "hello", emitter)

        val handle = emitterRegistry.get(conversationId)
        assertSame(emitter, handle?.emitter)
        assertTrue(handle?.cancelled?.get() == false)
    }

    @Test
    fun `streamChat flips the cancelled flag and deregisters on emitter completion`() {
        every { conversationService.saveUserMessage(conversationId, "hello") } returns
            history + userMessage("hello")
        val onCompletionSlot = slot<Runnable>()
        every { emitter.onCompletion(capture(onCompletionSlot)) } returns Unit

        chatService.streamChat(conversationId, ownerId, "hello", emitter)
        val handle = emitterRegistry.get(conversationId)!!

        onCompletionSlot.captured.run()

        assertTrue(handle.cancelled.get())
        assertNull(emitterRegistry.get(conversationId))
    }

    @Test
    fun `streamStatus reports generating with partial text when a stream is in flight`(){
        every { generatingStatusService.getGeneratingProgress(conversationId) } returns "partial tex"

        val result = chatService.streamStatus(conversationId, ownerId)

        assertEquals(StreamStatusResponse(generating = true, partial = "partial tex"), result)
    }

    @Test
    fun `streamStatus reports not generating when nothing is in flight`(){
        every { generatingStatusService.getGeneratingProgress(conversationId) } returns null

        val result = chatService.streamStatus(conversationId, ownerId)

        assertEquals(StreamStatusResponse(generating = false), result)
    }
}
