package org.timpeng.chatbot.conversation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.chat.ChatResponse
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmResponse
import org.timpeng.chatbot.redis.RedisService
import java.util.Optional
import kotlin.test.assertEquals

class ConversationServiceTest {
    private val conversationHistoryService: ConversationHistoryService = mockk()
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()
    private val redisService: RedisService = mockk()

    private lateinit var conversationService: ConversationService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        conversationService = ConversationService(conversationRepository, messageRepository, redisService, conversationHistoryService)
    }

    // saveMessage

    @Test
    fun `saveMessage saves message with correct conversation, role, and content`() {
        val slot = slot<Message>()
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.save(capture(slot)) } answers { slot.captured }
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit

        val result = conversationService.saveMessage(conversationId, Role.USER, "Hello")

        assertEquals(conversation, result.conversation)
        assertEquals(Role.USER, result.role)
        assertEquals("Hello", result.content)
        verify { messageRepository.save(any()) }
    }

    @Test
    fun `saveMessage writes to redis outside an active transaction`() {
        // no Spring transaction is bound in this unit test, so the cache write happens inline
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.save(any()) } answers { firstArg() }
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit

        conversationService.saveMessage(conversationId, Role.USER, "Hello")

        verify { redisService.saveChatMessage(conversationId, match { it.content == "Hello" }) }
    }

    @Test
    fun `saveMessage saves assistant message correctly`() {
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit
        every { messageRepository.save(any()) } answers { firstArg() }

        val result = conversationService.saveMessage(conversationId, Role.ASSISTANT, "How can I help?")

        assertEquals(Role.ASSISTANT, result.role)
        assertEquals("How can I help?", result.content)
    }

    @Test
    fun `saveMessage throws when conversation does not exist`() {
        every { conversationRepository.findByUuid(conversationId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            conversationService.saveMessage(conversationId, Role.USER, "Hello")
        }
    }

    // saveUserMessage / saveAssistantMessage

    @Test
    fun `saveUserMessage appends the new message to history from historyService`() {
        val history = listOf(Message(id = 1L, conversation = conversation, role = Role.USER, content = "previous"))
        every { conversationHistoryService.getHistory(conversationId) } returns history
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.save(any()) } answers { firstArg() }
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit

        val result = conversationService.saveUserMessage(conversationId, "Hello")

        assertEquals(2, result.size)
        assertEquals("previous", result[0].content)
        assertEquals(Role.USER, result[1].role)
        assertEquals("Hello", result[1].content)
    }

    @Test
    fun `saveAssistantMessage saves the assistant reply and returns a ChatResponse`() {
        val llmResponse = LlmResponse(message = "Hi!", model = "gemini-3-flash-preview", latencyMs = 42L)
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.save(any()) } answers { firstArg() }
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit

        val result = conversationService.saveAssistantMessage(conversationId, llmResponse)

        assertEquals(ChatResponse("Hi!", "gemini-3-flash-preview", 42L), result)
        verify { messageRepository.save(match { it.role == Role.ASSISTANT && it.content == "Hi!" }) }
    }
}
