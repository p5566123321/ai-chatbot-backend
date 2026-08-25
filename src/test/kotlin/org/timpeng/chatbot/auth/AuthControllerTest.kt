package org.timpeng.chatbot.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.timpeng.chatbot.auth.user.UserResponse
import java.time.Instant
import java.time.LocalDateTime

@WebMvcTest(controllers = [AuthController::class])
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var authService: AuthService

    // register validation (RegisterRequest)

    @Test
    fun `POST register with a well-formed body returns 201`() {
        every { authService.register("user@example.com", "hunter2pass") } returns
                UserResponse(1L, "user@example.com", LocalDateTime.now(), messageEmbeddingEnabled = false)

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"user@example.com","password":"hunter2pass"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("user@example.com"))
    }

    @Test
    fun `POST register with a malformed email returns 400 without calling the service`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"not-an-email","password":"hunter2pass"}""")
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { authService.register(any(), any()) }
    }

    @Test
    fun `POST register with a blank password returns 400`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"user@example.com","password":""}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST register with a too-short password returns 400`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"user@example.com","password":"short"}""")
        )
            .andExpect(status().isBadRequest)
    }

    // login validation (LoginRequest) — deliberately more lenient, see LoginRequest's kdoc

    @Test
    fun `POST login with a well-formed body returns 200`() {
        every { authService.login("user@example.com", "anything") } returns
            AuthResponse("signed-token", Instant.now())

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"user@example.com","password":"anything"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("signed-token"))
    }

    @Test
    fun `POST login with a blank email returns 400 without calling the service`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"","password":"anything"}""")
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { authService.login(any(), any()) }
    }
}
