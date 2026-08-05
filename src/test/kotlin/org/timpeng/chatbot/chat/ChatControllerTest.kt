package org.timpeng.chatbot.chat

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.llm.LlmException
import tools.jackson.databind.ObjectMapper


@WebMvcTest(controllers = [ChatController::class])
class ChatControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var chatService: ChatService

    @Test
    fun `POST messages returns 200 with llm response`() {
        val request = ChatRequest(message = "Hello")
        val response = ChatResponse(message = "Hi there!", model = "gemini-3-flash-preview", latencyMs = 123L)

        every { chatService.chat("test-uuid", "Hello") } returns response

        mockMvc.perform(
            post("/api/conversations/test-uuid/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Hi there!"))
            .andExpect(jsonPath("$.model").value("gemini-3-flash-preview"))
            .andExpect(jsonPath("$.latencyMs").value(123))

        verify { chatService.chat("test-uuid", "Hello") }
    }

    @Test
    fun `POST messages returns 400 when body is malformed`() {
        mockMvc.perform(
            post("/api/conversations/test-uuid/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST messages returns 503 when llm provider is unavailable`() {
        val request = ChatRequest(message = "Hello")

        every { chatService.chat("test-uuid", "Hello") } throws LlmException("[Gemini API] unavailable")

        mockMvc.perform(
            post("/api/conversations/test-uuid/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("LLM_UNAVAILABLE"))
    }

    @Test
    fun `streamChat should return event stream content type`(){
        val request = ChatRequest(message = "Hello")

        every { chatService.streamChat(any(), any(), any()) } just Runs

        mockMvc.perform(
            post("/api/conversations/test-uuid/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE))
    }

    @Test
    fun `streamChat should delegate to chat service`(){
        val request = ChatRequest(message = "Hello")

        every { chatService.streamChat(any(), any(), any()) } just Runs

        mockMvc.perform(
            post("/api/conversations/test-uuid/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )

        verify { chatService.streamChat("test-uuid", "Hello", any(SseEmitter::class)) }
    }
}
