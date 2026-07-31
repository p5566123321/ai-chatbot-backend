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

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)
    private val history = listOf(
        Message(id = 1L, conversation = conversation, role = Role.USER, content = "previous")
    )

    @BeforeEach
    fun setUp() {
        chatService = ChatService(conversationService, llmProvider)
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
}
