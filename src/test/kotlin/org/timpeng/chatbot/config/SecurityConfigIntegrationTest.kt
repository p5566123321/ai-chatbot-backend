package org.timpeng.chatbot.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.timpeng.chatbot.auth.jwt.JwtService
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.ConversationRepository
import org.timpeng.chatbot.exception.ErrorResponse
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Exercises `SecurityConfig`'s real filter chain end to end (unlike the `@WebMvcTest` controller
 * slices, which populate `SecurityContext` directly and never load `SecurityConfig` or
 * `JwtAuthenticationFilter` at all — see `ConversationControllerTest`'s own comment on that).
 * `ChatSseIntegrationTest` already covers the *authenticated* path (including the ASYNC-dispatch
 * edge case) through this same real chain; this class covers the other half — that a request
 * lacking a valid token is actually rejected, with the specific 401 shape `SecurityConfig`'s
 * custom `authenticationEntryPoint` produces, and that the declared `permitAll` routes stay open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var conversationRepository: ConversationRepository

    @Autowired
    private lateinit var jwtService: JwtService

    private lateinit var webTestClient: WebTestClient
    private var createdUserEmail: String? = null

    @AfterEach
    fun tearDown() {
        createdUserEmail?.let { email ->
            userRepository.findByEmail(email).ifPresent { user ->
                conversationRepository.findAll().filter { it.ownerId == user.id }.forEach(conversationRepository::delete)
                userRepository.delete(user)
            }
        }
        createdUserEmail = null
    }

    private fun client(): WebTestClient {
        if (!::webTestClient.isInitialized) {
            webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
        }
        return webTestClient
    }

    @Test
    fun `protected endpoint with no Authorization header returns 401 with SecurityConfig's entry-point body`() {
        val body = client().post().uri("/api/conversations")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .returnResult(ErrorResponse::class.java)
            .responseBody
            .blockFirst()

        assertEquals(401, body?.status)
        assertEquals("Unauthorized", body?.error)
        assertEquals("Missing or invalid access token", body?.message)
    }

    @Test
    fun `protected endpoint with a garbage bearer token returns 401`() {
        client().post().uri("/api/conversations")
            .header("Authorization", "Bearer not-a-real-jwt")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `protected endpoint with a malformed Authorization header (no Bearer prefix) returns 401`() {
        client().post().uri("/api/conversations")
            .header("Authorization", "not-a-real-jwt")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `protected endpoint with a valid token succeeds`() {
        createdUserEmail = "sec-test-${UUID.randomUUID()}@example.com"
        val user = userRepository.save(User(email = createdUserEmail!!, passwordHash = "unused"))
        val token = jwtService.issue(user.id!!).token

        client().post().uri("/api/conversations")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `permitAll route (api auth register) is reachable with no token`() {
        createdUserEmail = "sec-test-${UUID.randomUUID()}@example.com"

        client().post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("email" to createdUserEmail, "password" to "hunter2pass"))
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `permitAll route (actuator health) is reachable with no token`() {
        client().get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
    }
}
