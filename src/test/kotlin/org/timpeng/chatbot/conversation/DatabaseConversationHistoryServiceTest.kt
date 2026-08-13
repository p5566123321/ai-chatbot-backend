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
import org.timpeng.chatbot.exception.ConversationNotFoundException
import java.util.Optional
import kotlin.test.assertEquals

class DatabaseConversationHistoryServiceTest {
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()

    private lateinit var databaseConversationHistoryService: DatabaseConversationHistoryService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        databaseConversationHistoryService = DatabaseConversationHistoryService(
            conversationRepository,
            messageRepository,
            SimpleMeterRegistry(),
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
}
