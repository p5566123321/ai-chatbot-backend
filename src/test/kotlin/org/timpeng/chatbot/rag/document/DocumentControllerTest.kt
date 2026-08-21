package org.timpeng.chatbot.rag.document

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.timpeng.chatbot.auth.jwt.JwtAuthenticationToken
import org.timpeng.chatbot.exception.DocumentNotFoundException
import java.time.Instant

// SecurityConfig's real filter chain isn't wired into this @WebMvcTest slice, so @CurrentUserId
// has nothing to resolve unless the SecurityContext is populated directly — see ChatControllerTest.
@WebMvcTest(controllers = [DocumentController::class])
class DocumentControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var documentService: DocumentService

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
    fun `POST documents uploads a file and returns 201 with Location and body`() {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello world".toByteArray())
        val document = DocumentJpaEntity(
            id = 1L, uuid = "doc-uuid", title = "notes.txt", content = "hello world",
            status = DocumentStatus.PENDING, uploadedAt = Instant.parse("2026-01-01T00:00:00Z"), userId = ownerId,
        )
        coEvery { documentService.upload(any(), ownerId) } returns document

        // upload() is a suspend controller method — Spring MVC dispatches it asynchronously
        // (via kotlinx-coroutines-reactor's Mono bridge), so the result has to be picked up in a
        // second dispatch rather than off the initial perform() response.
        val mvcResult = mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/documents/doc-uuid"))
            .andExpect(jsonPath("$.id").value("doc-uuid"))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `GET documents by id returns the mapped document`() {
        val document = DocumentJpaEntity(
            id = 1L, uuid = "doc-uuid", title = "notes.txt", content = "hello world",
            status = DocumentStatus.READY, uploadedAt = Instant.parse("2026-01-01T00:00:00Z"),
            userId = ownerId, totalChunks = 3,
        )
        every { documentService.getDocument("doc-uuid", ownerId) } returns document

        mockMvc.perform(get("/api/documents/doc-uuid"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("doc-uuid"))
            .andExpect(jsonPath("$.title").value("notes.txt"))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.totalChunks").value(3))
    }

    @Test
    fun `GET documents by id returns 404 when not found or not owned by the caller`() {
        every { documentService.getDocument("missing", ownerId) } throws
                DocumentNotFoundException("Document not found: missing")

        mockMvc.perform(get("/api/documents/missing"))
            .andExpect(status().isNotFound)
    }
}
