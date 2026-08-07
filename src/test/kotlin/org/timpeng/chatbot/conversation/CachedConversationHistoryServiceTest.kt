package org.timpeng.chatbot.conversation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.redis.ConversationCacheService
import kotlin.test.assertEquals

class CachedConversationHistoryServiceTest {
    private val conversationCacheService: ConversationCacheService = mockk()
    private val delegate: DatabaseConversationHistoryService = mockk()

    private lateinit var cachedConversationHistoryService: CachedConversationHistoryService

    private val conversationId = "test-uuid"
    private val conversation = Conversation(id = 1L, uuid = conversationId)

    @BeforeEach
    fun setUp() {
        cachedConversationHistoryService = CachedConversationHistoryService(
            conversationCacheService,
            delegate,
            SimpleMeterRegistry(),
        )
    }

    @Test
    fun `getHistory returns cached messages from redis without touching the database`() {
        val cached = listOf(
            Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello"),
        )
        every { conversationCacheService.getChatHistory(conversationId) } returns cached

        val result = cachedConversationHistoryService.getHistory(conversationId)

        assertEquals(cached, result)
        verify(exactly = 0) { delegate.getHistory(any()) }
    }

    @Test
    fun `getHistory falls back to the database and repopulates redis when cache is empty`() {
        val chronological = listOf(
            Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello"),
            Message(id = 2L, conversation = conversation, role = Role.ASSISTANT, content = "Hi!"),
        )
        every { conversationCacheService.getChatHistory(conversationId) } returns emptyList()
        every { delegate.getHistory(conversationId) } returns chronological
        every { conversationCacheService.saveChatMessageList(conversationId, chronological) } returns Unit

        val result = cachedConversationHistoryService.getHistory(conversationId)

        assertEquals(chronological, result)
        verify { conversationCacheService.saveChatMessageList(conversationId, chronological) }
    }

    @Test
    fun `getHistory propagates ConversationNotFoundException from the database delegate on a cache miss`() {
        every { conversationCacheService.getChatHistory(conversationId) } returns emptyList()
        every { delegate.getHistory(conversationId) } throws
            ConversationNotFoundException("Conversation not found: $conversationId")

        assertThrows<ConversationNotFoundException> {
            cachedConversationHistoryService.getHistory(conversationId)
        }
        verify(exactly = 0) { conversationCacheService.saveChatMessageList(any(), any()) }
    }
}
