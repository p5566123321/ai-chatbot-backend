package org.timpeng.chatbot.conversation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.timpeng.chatbot.auth.jwt.JwtAuthenticationToken
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.ConversationNotFoundException
import java.time.LocalDateTime

// SecurityConfig's real filter chain isn't wired into this @WebMvcTest slice, so @CurrentUserId
// has nothing to resolve unless the SecurityContext is populated directly — see ChatControllerTest.
@WebMvcTest(controllers = [ConversationController::class])
class ConversationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var conversationService: ConversationService

    @MockkBean
    private lateinit var conversationHistoryService: ConversationHistoryService

    private val ownerId = 1L

    @BeforeEach
    fun setUpAuth() {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(ownerId)
    }

    @AfterEach
    fun clearAuth() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `POST conversations creates a conversation and returns 201`() {
        val conversation = Conversation(id = 1L, uuid = "new-uuid", ownerId = ownerId, createdAt = LocalDateTime.now())
        every { conversationService.createConversation(ownerId) } returns conversation

        mockMvc.perform(post("/api/conversations"))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/conversations/new-uuid"))
            .andExpect(jsonPath("$.uuid").value("new-uuid"))
    }

    @Test
    fun `GET messages returns 404 for unknown conversation`() {
        every { conversationService.requireOwnedConversation("missing", ownerId) } throws
                ConversationNotFoundException("Conversation not found: missing")

        mockMvc.perform(get("/api/conversations/missing/messages"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET messages returns 404 for a conversation owned by someone else`() {
        every { conversationService.requireOwnedConversation("not-mine", ownerId) } throws
                ConversationNotFoundException("Conversation not found: not-mine")

        mockMvc.perform(get("/api/conversations/not-mine/messages"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET messages returns mapped history`() {
        val conversation = Conversation(id = 1L, uuid = "abc", ownerId = ownerId, createdAt = LocalDateTime.now())
        val message = Message(id = 1L, conversation = conversation, role = Role.USER, content = "hi", createdAt = LocalDateTime.now())
        every { conversationService.requireOwnedConversation("abc", ownerId) } returns conversation
        every { conversationHistoryService.getHistory("abc") } returns listOf(message)

        mockMvc.perform(get("/api/conversations/abc/messages"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].role").value("USER"))
            .andExpect(jsonPath("$[0].content").value("hi"))
    }
}
