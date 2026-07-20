package org.timpeng.chatbot.conversation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.redis.RedisService
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationServiceTest {
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()
    private val redisService: RedisService = mockk()

    private lateinit var conversationService: ConversationService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        conversationService = ConversationService(conversationRepository, messageRepository, redisService)
    }

    // getHistory

    @Test
    fun `getHistory returns cached messages from redis without touching the database`() {
        val cached = listOf(
            Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello"),
        )
        every { redisService.getChatHistory(conversationId) } returns cached

        val result = conversationService.getHistory(conversationId)

        assertEquals(cached, result)
        verify(exactly = 0) { conversationRepository.findByUuid(any()) }
        verify(exactly = 0) { messageRepository.findByConversationOrderByCreatedAtDesc(any(), any()) }
    }

    @Test
    fun `getHistory loads from database and repopulates redis when cache is empty`() {
        val oldest = Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello")
        val newest = Message(id = 2L, conversation = conversation, role = Role.ASSISTANT, content = "Hi!")
        // repository returns newest-first; getHistory must restore chronological order
        val descendingFromDb = listOf(newest, oldest)
        every { redisService.getChatHistory(conversationId) } returns emptyList()
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.findByConversationOrderByCreatedAtDesc(conversation, any()) } returns descendingFromDb
        every { redisService.saveChatMessage(conversationId, any()) } returns Unit

        val result = conversationService.getHistory(conversationId)

        assertEquals(listOf(oldest, newest), result)
        verify { redisService.saveChatMessage(conversationId, oldest) }
        verify { redisService.saveChatMessage(conversationId, newest) }
        verify(exactly = 0) { conversationRepository.save(any()) }
    }

    @Test
    fun `getHistory creates a new conversation when none exists yet`() {
        every { redisService.getChatHistory(conversationId) } returns emptyList()
        every { conversationRepository.findByUuid(conversationId) } returns Optional.empty()
        every { conversationRepository.save(any()) } returns conversation
        every { messageRepository.findByConversationOrderByCreatedAtDesc(conversation, any()) } returns emptyList()

        val result = conversationService.getHistory(conversationId)

        assertTrue(result.isEmpty())
        verify { conversationRepository.save(match { it.uuid == conversationId }) }
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
}
