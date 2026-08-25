package org.timpeng.chatbot.auth.user

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.timpeng.chatbot.auth.jwt.JwtAuthenticationToken
import java.time.LocalDateTime

// SecurityConfig's real filter chain isn't wired into this @WebMvcTest slice, so @CurrentUserId
// has nothing to resolve unless the SecurityContext is populated directly — see ConversationControllerTest.
@WebMvcTest(controllers = [UserController::class])
class UserControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var userService: UserService

    private val ownerId = 1L

    private fun userResponse(messageEmbeddingEnabled: Boolean = false) =
        UserResponse(ownerId, "user@example.com", LocalDateTime.now(), messageEmbeddingEnabled)

    @BeforeEach
    fun setUpAuth() {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(ownerId)
    }

    @AfterEach
    fun clearAuth() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `GET users me returns the caller's account`() {
        every { userService.getUser(ownerId) } returns userResponse()

        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("user@example.com"))
            .andExpect(jsonPath("$.messageEmbeddingEnabled").value(false))
    }

    @Test
    fun `PATCH message-embedding turns the switch on`() {
        every { userService.setMessageEmbeddingEnabled(ownerId, true) } returns
            userResponse(messageEmbeddingEnabled = true)

        mockMvc.perform(
            patch("/api/users/me/message-embedding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messageEmbeddingEnabled").value(true))

        verify { userService.setMessageEmbeddingEnabled(ownerId, true) }
    }

    @Test
    fun `PATCH message-embedding turns the switch off`() {
        every { userService.setMessageEmbeddingEnabled(ownerId, false) } returns userResponse()

        mockMvc.perform(
            patch("/api/users/me/message-embedding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":false}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messageEmbeddingEnabled").value(false))
    }
}
