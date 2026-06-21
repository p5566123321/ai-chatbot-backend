package org.timpeng.chatbot.conversation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import kotlin.test.assertEquals

class ConversationServiceTest {
    private val conversationRepository: ConversationRepository = mockk()
    private val messageRepository: MessageRepository = mockk()
    private lateinit var conversationService: ConversationService

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")

    @BeforeEach
    fun setUp() {
        conversationService = ConversationService(conversationRepository, messageRepository)
    }

    // getOrCreateConversation

    @Test
    fun `getOrCreateConversation returns existing conversation when found`() {
        every { conversationRepository.findByUuid("test-uuid") } returns conversation

        val result = conversationService.getOrCreateConversation("test-uuid")

        assertEquals(conversation, result)
        verify(exactly = 0) { conversationRepository.save(any()) }
    }

    @Test
    fun `getOrCreateConversation creates and saves new conversation when not found`() {
        val newConversation = Conversation(id = 2L, uuid = "new-uuid")

        every { conversationRepository.findByUuid("new-uuid") } returns null
        every { conversationRepository.save(any()) } returns newConversation

        val result = conversationService.getOrCreateConversation("new-uuid")

        assertEquals(newConversation, result)
        verify { conversationRepository.save(match { it.uuid == "new-uuid" && it.id == null }) }
    }

    // getMessages

    @Test
    fun `getMessages returns messages for the conversation`() {
        val messages = listOf(
            Message(id = 1L, conversation = conversation, role = Role.USER, content = "Hello"),
            Message(id = 2L, conversation = conversation, role = Role.ASSISTANT, content = "Hi!")
        )

        every { messageRepository.findByConversation(conversation) } returns messages

        val result = conversationService.getMessages(conversation)

        assertEquals(messages, result)
    }

    @Test
    fun `getMessages returns empty list when conversation has no messages`() {
        every { messageRepository.findByConversation(conversation) } returns emptyList()

        val result = conversationService.getMessages(conversation)

        assertEquals(emptyList(), result)
    }

    // addMessage

    @Test
    fun `addMessage saves message with correct conversation, role, and content`() {
        val slot = slot<Message>()
        every { messageRepository.save(capture(slot)) } answers { slot.captured }

        conversationService.addMessage(conversation, Role.USER, "Hello")

        val saved = slot.captured
        assertEquals(conversation, saved.conversation)
        assertEquals(Role.USER, saved.role)
        assertEquals("Hello", saved.content)
    }

    @Test
    fun `addMessage saves assistant message correctly`() {
        val slot = slot<Message>()
        every { messageRepository.save(capture(slot)) } answers { slot.captured }

        conversationService.addMessage(conversation, Role.ASSISTANT, "How can I help?")

        val saved = slot.captured
        assertEquals(Role.ASSISTANT, saved.role)
        assertEquals("How can I help?", saved.content)
    }
}