package org.timpeng.chatbot.conversation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
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

class ConversationHistoryServiceTest {
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()
    private val redisService: RedisService = mockk()

    private lateinit var conversationHistoryService: ConversationHistoryService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        conversationHistoryService = ConversationHistoryService(
            conversationRepository,
            messageRepository,
            redisService,
            SimpleMeterRegistry(),
        )
    }

    @Test
    fun `getHistory returns cached messages from redis without touching the database`() {
        val cached = listOf(
            Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello"),
        )
        every { redisService.getChatHistory(conversationId) } returns cached

        val result = conversationHistoryService.getHistory(conversationId)

        assertEquals(cached, result)
        verify(exactly = 0) { conversationRepository.findByUuid(any()) }
        verify(exactly = 0) { messageRepository.findByConversationOrderByCreatedAt(any(), any()) }
    }

    @Test
    fun `getHistory loads from database and repopulates redis when cache is empty`() {
        val oldest = Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello")
        val newest = Message(id = 2L, conversation = conversation, role = Role.ASSISTANT, content = "Hi!")
        val chronological = listOf(oldest, newest)
        every { redisService.getChatHistory(conversationId) } returns emptyList()
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.findByConversationOrderByCreatedAt(conversation, any()) } returns chronological
        every { redisService.saveChatMessageList(conversationId, chronological) } returns Unit

        val result = conversationHistoryService.getHistory(conversationId)

        assertEquals(chronological, result)
        verify { redisService.saveChatMessageList(conversationId, chronological) }
        verify(exactly = 0) { conversationRepository.save(any()) }
    }

    @Test
    fun `getHistory throws when conversation does not exist`() {
        every { redisService.getChatHistory(conversationId) } returns emptyList()
        every { conversationRepository.findByUuid(conversationId) } returns Optional.empty()

        assertThrows<ConversationNotFoundException> {
            conversationHistoryService.getHistory(conversationId)
        }
        verify(exactly = 0) { conversationRepository.save(any()) }
    }
}
