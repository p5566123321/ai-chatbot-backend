package org.timpeng.chatbot.conversation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import java.time.LocalDateTime

@WebMvcTest(controllers = [ConversationController::class])
class ConversationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var conversationService: ConversationService

    @MockkBean
    private lateinit var conversationHistoryService: ConversationHistoryService

    @Test
    fun `POST conversations creates a conversation and returns 201`() {
        val conversation = Conversation(id = 1L, uuid = "new-uuid", createdAt = LocalDateTime.now())
        every { conversationService.createConversation() } returns conversation

        mockMvc.perform(post("/api/conversations"))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/conversations/new-uuid"))
            .andExpect(jsonPath("$.uuid").value("new-uuid"))
    }

    @Test
    fun `GET messages returns 404 for unknown conversation`() {
        every { conversationHistoryService.getHistory("missing") } throws
            ConversationNotFoundException("Conversation not found: missing")

        mockMvc.perform(get("/api/conversations/missing/messages"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET messages returns mapped history`() {
        val conversation = Conversation(id = 1L, uuid = "abc", createdAt = LocalDateTime.now())
        val message = Message(id = 1L, conversation = conversation, role = Role.USER, content = "hi", createdAt = LocalDateTime.now())
        every { conversationHistoryService.getHistory("abc") } returns listOf(message)

        mockMvc.perform(get("/api/conversations/abc/messages"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].role").value("USER"))
            .andExpect(jsonPath("$[0].content").value("hi"))
    }
}
