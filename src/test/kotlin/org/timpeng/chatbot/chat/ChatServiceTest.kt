package org.timpeng.chatbot.chat

import io.mockk.every
import io.mockk.mockk
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
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.llm.LlmResponse
import org.timpeng.chatbot.redis.GeneratingStatusService
import java.io.IOException
import kotlin.test.assertEquals

class ChatServiceTest {

    private val conversationService: ConversationService = mockk()
    private val llmProvider: LlmProvider = mockk()
    private val generatingStatusService: GeneratingStatusService = mockk(relaxed = true)
    private lateinit var chatService: ChatService
    private val emitter: SseEmitter = mockk(relaxed = true)

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)
    private val history = listOf(
        Message(id = 1L, conversation = conversation, role = Role.USER, content = "previous")
    )

    @BeforeEach
    fun setUp() {
        chatService = ChatService(conversationService, llmProvider, generatingStatusService)
    }

    private fun userMessage(content: String) =
        Message(id = 2L, conversation = conversation, role = Role.USER, content = content)

    private fun assistantMessage(content: String) =
        Message(id = 3L, conversation = conversation, role = Role.ASSISTANT, content = content)

    @Test
    fun `chat returns response with llm message, model, and latency`() {
        val llmResponse = LlmResponse(message = "Hi there!", model = "gemini-2.0-flash", latencyMs = 123L)
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } returns llmResponse
        every { conversationService.saveAssistantMessage(conversationId, llmResponse) } returns
            ChatResponse("Hi there!", "gemini-2.0-flash", 123L)

        val result = chatService.chat(conversationId, "Hello")

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

        chatService.chat(conversationId, "Hello")

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

        chatService.chat(conversationId, "Hello")

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

        val result = chatService.chat(newConversationId, "Hi")

        assertEquals("Hello!", result.message)
        verify { conversationService.saveUserMessage(newConversationId, "Hi") }
    }

    @Test
    fun `chat throws ChatException when llm provider fails`() {
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } throws RuntimeException("LLM unavailable")

        assertThrows<ChatException> {
            chatService.chat(conversationId, "Hello")
        }
    }

    @Test
    fun `chat does not save assistant message when llm provider fails`() {
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "Hello") } returns messagesWithUser
        every { llmProvider.generate(messagesWithUser) } throws RuntimeException("LLM unavailable")

        runCatching { chatService.chat(conversationId, "Hello") }

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

        val result = chatService.chat(conversationId, "Hello")

        verify { conversationService.saveAssistantMessage(conversationId, llmResponse) }
        assertEquals(markdown, result.message)
    }

    @Test
    fun `stream response should send each chunk as SEE event`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("this", "is", "test", "response").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test response")

        chatService.streamChat(conversationId, "hello", emitter)

        verify(timeout = 2000) { emitter.complete() }
        verify(exactly = 4){ emitter.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `streamResponse should complete emitter after all chunks sent`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("this", "is", "test", "response").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test response")

        chatService.streamChat(conversationId, "hello", emitter)

        verify(timeout = 2000) { emitter.complete() }
    }

    @Test
    fun `streamResponse sends an error event and completes gracefully when llmProvider throws`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } throws RuntimeException("LLM unavailable")
        chatService.streamChat(conversationId, "hello", emitter)

        // The client can already be told about the failure via the error event's payload, so a
        // successful send lets us close cleanly instead of aborting the connection.
        verify(timeout = 2000) { emitter.complete() }
        verify {
            emitter.send(match<SseEmitter.SseEventBuilder> {
                it.build().any { d -> d.data == "LLM unavailable" }
            })
        }
        verify(exactly = 0) { emitter.completeWithError(any()) }
    }

    @Test
    fun `should send chunk values`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("A", "B", "C").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("ABC")

        chatService.streamChat(conversationId, "hello", emitter)

        verify(timeout = 2000) { emitter.complete() }
        verifyOrder{
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "A" } })
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "B" } })
            emitter.send(match<SseEmitter.SseEventBuilder> { it.build().any { d -> d.data == "C" } })
            emitter.complete()
        }
    }

    @Test
    fun `should stop and complete with error if emitter throws IOException`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("A", "B", "C").forEach(onChunk)
        }
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws IOException("client disconnected")
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("A")

        chatService.streamChat(conversationId, "hello", emitter)

        verify(timeout = 2000) { emitter.completeWithError(any<IOException>()) }
    }

    @Test
    fun `streamChat marks generating before dispatch and clears it after a successful finish`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("this", "is", "test").forEach(onChunk)
        }
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) } returns
            assistantMessage("this is test")

        chatService.streamChat(conversationId, "hello", emitter)

        // markGenerating happens synchronously before the async dispatch, clearGenerating only
        // once the background work is done — waiting on emitter.complete() also waits for that.
        verify { generatingStatusService.markGenerating(conversationId) }
        verify(timeout = 2000) { emitter.complete() }
        verify(timeout = 2000) { generatingStatusService.clearGenerating(conversationId) }
    }

    @Test
    fun `streamChat clears generating status even when llmProvider throws`(){
        val messagesWithUser = history + userMessage("Hello")

        every { conversationService.saveUserMessage(conversationId, "hello") } returns messagesWithUser
        every { llmProvider.streamGenerate(messagesWithUser, any()) } throws RuntimeException("LLM unavailable")

        chatService.streamChat(conversationId, "hello", emitter)

        verify(timeout = 2000) { generatingStatusService.clearGenerating(conversationId) }
    }

    @Test
    fun `streamStatus reports generating with partial text when a stream is in flight`(){
        every { generatingStatusService.getGeneratingProgress(conversationId) } returns "partial tex"

        val result = chatService.streamStatus(conversationId)

        assertEquals(StreamStatusResponse(generating = true, partial = "partial tex"), result)
    }

    @Test
    fun `streamStatus reports not generating when nothing is in flight`(){
        every { generatingStatusService.getGeneratingProgress(conversationId) } returns null

        val result = chatService.streamStatus(conversationId)

        assertEquals(StreamStatusResponse(generating = false), result)
    }

}
