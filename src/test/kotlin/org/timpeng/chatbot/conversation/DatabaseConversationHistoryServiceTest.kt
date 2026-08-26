package org.timpeng.chatbot.conversation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.ConversationNotFoundException
import java.util.Optional
import kotlin.test.assertEquals

class DatabaseConversationHistoryServiceTest {
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()
    private val userRepository: UserRepository = mockk()

    private lateinit var databaseConversationHistoryService: DatabaseConversationHistoryService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        databaseConversationHistoryService = DatabaseConversationHistoryService(
            conversationRepository,
            messageRepository,
            SimpleMeterRegistry(),
            userRepository,
            maxMessages = 10,
        )
    }

    @Test
    fun `getHistory loads messages from the database in order`() {
        val oldest = Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello")
        val newest = Message(id = 2L, conversation = conversation, role = Role.ASSISTANT, content = "Hi!")
        val chronological = listOf(oldest, newest)
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(conversation)
        every { messageRepository.findByConversationOrderByCreatedAt(conversation, any()) } returns chronological

        val result = databaseConversationHistoryService.getHistory(conversationId)

        assertEquals(chronological, result)
    }

    @Test
    fun `getHistory throws when conversation does not exist`() {
        every { conversationRepository.findByUuid(conversationId) } returns Optional.empty()

        assertThrows<ConversationNotFoundException> {
            databaseConversationHistoryService.getHistory(conversationId)
        }
        verify(exactly = 0) { messageRepository.findByConversationOrderByCreatedAt(any(), any()) }
    }

    @Test
    fun `getHistory uses the owner's history-max-messages override instead of the global default`() {
        val ownerId = 42L
        val owned = conversation.copy(ownerId = ownerId)
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(owned)
        every { userRepository.findById(ownerId) } returns Optional.of(
            User(id = ownerId, email = "user@example.com", passwordHash = "hashed", historyMaxMessages = 3)
        )
        every { messageRepository.findByConversationOrderByCreatedAt(owned, any()) } returns emptyList()

        databaseConversationHistoryService.getHistory(conversationId)

        verify {
            messageRepository.findByConversationOrderByCreatedAt(owned, match { it.pageSize == 3 })
        }
    }

    @Test
    fun `getHistory falls back to the global default when the owner has no override`() {
        val ownerId = 42L
        val owned = conversation.copy(ownerId = ownerId)
        every { conversationRepository.findByUuid(conversationId) } returns Optional.of(owned)
        every { userRepository.findById(ownerId) } returns Optional.of(
            User(id = ownerId, email = "user@example.com", passwordHash = "hashed")
        )
        every { messageRepository.findByConversationOrderByCreatedAt(owned, any()) } returns emptyList()

        databaseConversationHistoryService.getHistory(conversationId)

        verify {
            messageRepository.findByConversationOrderByCreatedAt(owned, match { it.pageSize == 10 })
        }
    }
}
