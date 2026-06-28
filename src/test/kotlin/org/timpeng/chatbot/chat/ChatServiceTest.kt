package org.timpeng.chatbot.chat

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.llm.LlmResponse
import kotlin.test.assertEquals

class ChatServiceTest {

    private val conversationService: ConversationService = mockk()
    private val llmProvider: LlmProvider = mockk()
    private lateinit var chatService: ChatService

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")
    private val messages = listOf(
        Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello")
    )

    @BeforeEach
    fun setUp() {
        chatService = ChatService(conversationService, llmProvider)
    }

    @Test
    fun `chat returns response with llm message, model, and latency`() {
        val llmResponse = LlmResponse(message = "Hi there!", model = "gemini-2.0-flash", latencyMs = 123L)

        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } returns llmResponse

        val result = chatService.chat("test-uuid", "Hello")

        assertEquals(ChatResponse("Hi there!", "gemini-2.0-flash", 123L), result)
    }

    @Test
    fun `chat saves user message before calling llm`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)

        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } returns llmResponse

        chatService.chat("test-uuid", "Hello")

        verify { conversationService.addMessage(conversation, Role.USER, "Hello") }
    }

    @Test
    fun `chat saves assistant message after llm responds`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)

        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } returns llmResponse

        chatService.chat("test-uuid", "Hello")

        verify { conversationService.addMessage(conversation, Role.ASSISTANT, "Hi!") }
    }

    @Test
    fun `chat creates new conversation when id is not found`() {
        val newConversation = Conversation(id = 2L, uuid = "new-uuid")
        val llmResponse = LlmResponse(message = "Hello!", model = "gemini-2.0-flash", latencyMs = 80L)

        every { conversationService.getOrCreateConversation("new-uuid") } returns newConversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(newConversation) } returns emptyList()
        every { llmProvider.generate(emptyList()) } returns llmResponse

        val result = chatService.chat("new-uuid", "Hi")

        assertEquals("Hello!", result.message)
        verify { conversationService.getOrCreateConversation("new-uuid") }
    }

    @Test
    fun `chat throws ChatException when llm provider fails`() {
        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } throws RuntimeException("LLM unavailable")

        assertThrows<ChatException> {
            chatService.chat("test-uuid", "Hello")
        }
    }

    @Test
    fun `chat does not save assistant message when llm provider fails`() {
        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } throws RuntimeException("LLM unavailable")

        runCatching { chatService.chat("test-uuid", "Hello") }

        verify(exactly = 0) { conversationService.addMessage(conversation, Role.ASSISTANT, any()) }
    }

    @Test
    fun `chat returns raw markdown in response without html conversion`() {
        val markdown = "**bold**"
        val llmResponse = LlmResponse(message = markdown, model = "gemini-2.0-flash", latencyMs = 10L)

        every { conversationService.getOrCreateConversation("test-uuid") } returns conversation
        every { conversationService.addMessage(any(), any(), any()) } returns Unit
        every { conversationService.getMessages(conversation) } returns messages
        every { llmProvider.generate(messages) } returns llmResponse

        val result = chatService.chat("test-uuid", "Hello")

        verify { conversationService.addMessage(conversation, Role.ASSISTANT, markdown) }
        assertEquals(markdown, result.message)
    }
}
