package org.timpeng.chatbot.chat

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.timpeng.chatbot.queue.DlqEntry
import org.timpeng.chatbot.queue.DlqReader
import java.time.Instant

@WebMvcTest(controllers = [ChatDlqController::class])
class ChatDlqControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var dlqReader: DlqReader

    @Test
    fun `GET dlq returns count and entries from the chat stream`() {
        every { dlqReader.count("queue:chat") } returns 2L
        every { dlqReader.list("queue:chat", 50) } returns listOf(
            DlqEntry(
                id = "1-1",
                payload = """{"conversationId":"abc"}""",
                reason = "boom",
                attempt = 3,
                failedAt = Instant.parse("2026-08-08T10:00:00Z"),
            )
        )

        mockMvc.perform(get("/api/admin/queues/chat/dlq"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.entries[0].id").value("1-1"))
            .andExpect(jsonPath("$.entries[0].reason").value("boom"))
            .andExpect(jsonPath("$.entries[0].attempt").value(3))
    }

    @Test
    fun `GET dlq defaults limit to 50`() {
        every { dlqReader.count("queue:chat") } returns 0L
        every { dlqReader.list("queue:chat", 50) } returns emptyList()

        mockMvc.perform(get("/api/admin/queues/chat/dlq"))
            .andExpect(status().isOk)

        verify { dlqReader.list("queue:chat", 50) }
    }

    @Test
    fun `GET dlq passes through a caller-supplied limit`() {
        every { dlqReader.count("queue:chat") } returns 0L
        every { dlqReader.list("queue:chat", 10) } returns emptyList()

        mockMvc.perform(get("/api/admin/queues/chat/dlq").param("limit", "10"))
            .andExpect(status().isOk)

        verify { dlqReader.list("queue:chat", 10) }
    }

    @Test
    fun `GET dlq clamps an out-of-range limit instead of passing it straight to Redis`() {
        every { dlqReader.count("queue:chat") } returns 0L
        every { dlqReader.list("queue:chat", 500) } returns emptyList()

        mockMvc.perform(get("/api/admin/queues/chat/dlq").param("limit", "999999"))
            .andExpect(status().isOk)

        verify { dlqReader.list("queue:chat", 500) }
    }
}
