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

        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } returns llmResponse
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, "Hi there!") } returns
            assistantMessage("Hi there!")

        val result = chatService.chat(conversationId, "Hello")

        assertEquals(ChatResponse("Hi there!", "gemini-2.0-flash", 123L), result)
    }

    @Test
    fun `chat saves user message before calling llm`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)

        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } returns llmResponse
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, "Hi!") } returns assistantMessage("Hi!")

        chatService.chat(conversationId, "Hello")

        verify { conversationService.saveMessage(conversationId, Role.USER, "Hello") }
    }

    @Test
    fun `chat saves assistant message after llm responds`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-2.0-flash", latencyMs = 50L)

        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } returns llmResponse
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, "Hi!") } returns assistantMessage("Hi!")

        chatService.chat(conversationId, "Hello")

        verify { conversationService.saveMessage(conversationId, Role.ASSISTANT, "Hi!") }
    }

    @Test
    fun `chat works for a conversation with no prior history`() {
        val newConversationId = "new-uuid"
        val newConversation = Conversation(id = 2L, uuid = newConversationId)
        val newUserMessage = Message(id = 4L, conversation = newConversation, role = Role.USER, content = "Hi")
        val llmResponse = LlmResponse(message = "Hello!", model = "gemini-2.0-flash", latencyMs = 80L)

        every { conversationService.getHistory(newConversationId) } returns emptyList()
        every { conversationService.saveMessage(newConversationId, Role.USER, "Hi") } returns newUserMessage
        every { llmProvider.generate(listOf(newUserMessage)) } returns llmResponse
        every { conversationService.saveMessage(newConversationId, Role.ASSISTANT, "Hello!") } returns
            Message(id = 5L, conversation = newConversation, role = Role.ASSISTANT, content = "Hello!")

        val result = chatService.chat(newConversationId, "Hi")

        assertEquals("Hello!", result.message)
        verify { conversationService.getHistory(newConversationId) }
    }

    @Test
    fun `chat throws ChatException when llm provider fails`() {
        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } throws RuntimeException("LLM unavailable")

        assertThrows<ChatException> {
            chatService.chat(conversationId, "Hello")
        }
    }

    @Test
    fun `chat does not save assistant message when llm provider fails`() {
        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } throws RuntimeException("LLM unavailable")

        runCatching { chatService.chat(conversationId, "Hello") }

        verify(exactly = 0) { conversationService.saveMessage(conversationId, Role.ASSISTANT, any()) }
    }

    @Test
    fun `chat returns raw markdown in response without html conversion`() {
        val markdown = "**bold**"
        val llmResponse = LlmResponse(message = markdown, model = "gemini-2.0-flash", latencyMs = 10L)

        every { conversationService.getHistory(conversationId) } returns history
        every { conversationService.saveMessage(conversationId, Role.USER, "Hello") } returns userMessage("Hello")
        every { llmProvider.generate(history + userMessage("Hello")) } returns llmResponse
        every { conversationService.saveMessage(conversationId, Role.ASSISTANT, markdown) } returns
            assistantMessage(markdown)

        val result = chatService.chat(conversationId, "Hello")

        verify { conversationService.saveMessage(conversationId, Role.ASSISTANT, markdown) }
        assertEquals(markdown, result.message)
    }
}
