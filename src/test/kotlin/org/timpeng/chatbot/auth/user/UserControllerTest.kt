package org.timpeng.chatbot.auth.user

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.hamcrest.Matchers.hasItem
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

    private fun userResponse(
        messageEmbeddingEnabled: Boolean = false,
        geminiSettings: GeminiSettings = GeminiSettings(),
        hasGeminiApiKey: Boolean = false,
    ) = UserResponse(
        ownerId, "user@example.com", LocalDateTime.now(), messageEmbeddingEnabled, geminiSettings, hasGeminiApiKey
    )

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

    @Test
    fun `PATCH gemini-settings applies the given overrides`() {
        val settings = GeminiSettings(
            systemInstruction = "Be concise",
            temperature = 0.7f,
            topP = 0.9f,
            topK = 40f,
            candidateCount = 1,
            maxOutputTokens = 2048,
        )
        every { userService.updateGeminiSettings(ownerId, any()) } returns userResponse(geminiSettings = settings)

        mockMvc.perform(
            patch("/api/users/me/gemini-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"systemInstruction":"Be concise","temperature":0.7,"topP":0.9,"topK":40,"candidateCount":1,"maxOutputTokens":2048}"""
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.geminiSettings.systemInstruction").value("Be concise"))
            .andExpect(jsonPath("$.geminiSettings.temperature").value(0.7))
            .andExpect(jsonPath("$.geminiSettings.maxOutputTokens").value(2048))

        verify {
            userService.updateGeminiSettings(
                ownerId,
                match { it.systemInstruction == "Be concise" && it.maxOutputTokens == 2048 }
            )
        }
    }

    @Test
    fun `PATCH gemini-settings rejects an out-of-range temperature`() {
        mockMvc.perform(
            patch("/api/users/me/gemini-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"temperature":3.5}""")
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { userService.updateGeminiSettings(any(), any()) }
    }

    @Test
    fun `PATCH gemini-settings clears overrides when fields are omitted`() {
        every { userService.updateGeminiSettings(ownerId, any()) } returns userResponse()

        mockMvc.perform(
            patch("/api/users/me/gemini-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isOk)

        verify {
            userService.updateGeminiSettings(ownerId, match { it.toSettings() == GeminiSettings() })
        }
    }

    @Test
    fun `GET gemini-models returns the whitelist`() {
        mockMvc.perform(get("/api/users/me/gemini-models"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasItem("gemini-2.5-pro")))
    }

    @Test
    fun `PATCH gemini-api-key stores a key and reports hasGeminiApiKey`() {
        every { userService.updateGeminiApiKey(ownerId, any()) } returns
            userResponse(hasGeminiApiKey = true)

        mockMvc.perform(
            patch("/api/users/me/gemini-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"apiKey":"sk-real-key"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasGeminiApiKey").value(true))

        verify {
            userService.updateGeminiApiKey(ownerId, match { it.apiKey == "sk-real-key" })
        }
    }

    @Test
    fun `PATCH gemini-api-key with null apiKey clears the stored key`() {
        every { userService.updateGeminiApiKey(ownerId, any()) } returns
            userResponse(hasGeminiApiKey = false)

        mockMvc.perform(
            patch("/api/users/me/gemini-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"apiKey":null}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasGeminiApiKey").value(false))

        verify {
            userService.updateGeminiApiKey(ownerId, match { it.apiKey == null })
        }
    }

    @Test
    fun `PATCH gemini-api-key rejects an overly long key`() {
        mockMvc.perform(
            patch("/api/users/me/gemini-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"apiKey":"${"a".repeat(201)}"}""")
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { userService.updateGeminiApiKey(any(), any()) }
    }
}
