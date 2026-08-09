package org.timpeng.chatbot.chat

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.timpeng.chatbot.auth.JwtService
import org.timpeng.chatbot.auth.User
import org.timpeng.chatbot.auth.UserRepository
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.ConversationRepository
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.redis.GeneratingStatusService
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatSseIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var conversationRepository: ConversationRepository

    @Autowired
    private lateinit var messageRepository: MessageRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var generatingStatusService: GeneratingStatusService

    @MockkBean
    private lateinit var llmProvider: LlmProvider

    private lateinit var webTestClient: WebTestClient
    private var ownerId: Long = 0
    private lateinit var authHeader: String
    private lateinit var conversationId: String
    private lateinit var streamUri: String
    private lateinit var statusUri: String

    @BeforeEach
    fun setUp() {
        webTestClient = WebTestClient.bindToServer(JdkClientHttpConnector())
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(10))
            .build()

        // SecurityConfig requires a bearer token on every conversation-scoped endpoint (ADR-007),
        // and ownership is enforced against whoever's userId is in that token — so every request
        // below needs both a real conversation row and a real, matching, signed token.
        val user = userRepository.save(User(email = "sse-test-${UUID.randomUUID()}@example.com", passwordHash = "unused"))
        ownerId = user.id!!
        authHeader = "Bearer ${jwtService.issue(ownerId).token}"

        conversationId = UUID.randomUUID().toString()
        conversationRepository.save(Conversation(uuid = conversationId, ownerId = ownerId))
        streamUri = "/api/conversations/$conversationId/messages/stream"
        statusUri = "/api/conversations/$conversationId/messages/stream/status"
    }

    @AfterEach
    fun tearDown() {
        generatingStatusService.clearGenerating(conversationId)
        conversationRepository.findByUuid(conversationId).ifPresent { conversation ->
            messageRepository.deleteAll(
                messageRepository.findByConversationOrderByCreatedAt(conversation, Pageable.unpaged())
            )
            conversationRepository.delete(conversation)
        }
        userRepository.deleteById(ownerId)
    }

    @Test
    fun `stream endpoint sends each chunk as an SSE event`() {
        every { llmProvider.streamGenerate(any(), any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("Hello", " ", "world").forEach(onChunk)
        }

        val body = webTestClient.post()
            .uri(streamUri)
            .header("Authorization", authHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatRequest(message = "Hi"))
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(String::class.java)
            .responseBody
            .collectList()
            .block(Duration.ofSeconds(10))

        val joined = body.orEmpty().joinToString("\n")

        assertTrue(joined.contains("Hello"))
        assertTrue(joined.contains("world"))
    }

    @Test
    fun `stream failure after chunks persists partial assistant message and sends an error event`() {
        every { llmProvider.streamGenerate(any(), any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            listOf("Hello", " ", "world").forEach(onChunk)
            throw RuntimeException("boom")
        }

        // WebTestClient's SSE-aware decoder splits event name / data into structured fields, so
        // decode as ServerSentEvent<String> rather than raw String to check the "error" event
        // name directly instead of guessing at how it's framed on the wire.
        val events = webTestClient.post()
            .uri(streamUri)
            .header("Authorization", authHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatRequest(message = "Hi"))
            .exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .onErrorResume { Flux.empty() }
            .collectList()
            .block(Duration.ofSeconds(10))
            .orEmpty()

        assertTrue(events.any { it.event() == "error" && it.data() == "boom" })

        val conversation = conversationRepository.findByUuid(conversationId).orElseThrow()
        val assistantMessages = messageRepository
            .findByConversationOrderByCreatedAt(conversation, Pageable.unpaged())
            .filter { it.role == Role.ASSISTANT }

        assertEquals(1, assistantMessages.size)
        assertTrue(assistantMessages[0].content.contains("Hello world"))
        assertTrue(assistantMessages[0].content.contains("[回覆中斷]"))
    }

    @Test
    fun `stream failure before any chunk persists nothing but still sends an error event`() {
        every { llmProvider.streamGenerate(any(), any()) } throws RuntimeException("boom")

        // The response is now committed at 200 OK as soon as the emitter is returned (generation
        // runs off the request thread), so a failure with zero chunks sent no longer surfaces as
        // a 5xx status — the only client-visible signal is the explicit SSE error event.
        val events = webTestClient.post()
            .uri(streamUri)
            .header("Authorization", authHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatRequest(message = "Hi"))
            .exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .onErrorResume { Flux.empty() }
            .collectList()
            .block(Duration.ofSeconds(10))
            .orEmpty()

        assertTrue(events.any { it.event() == "error" && it.data() == "boom" })

        val conversation = conversationRepository.findByUuid(conversationId).orElseThrow()
        val assistantMessages = messageRepository
            .findByConversationOrderByCreatedAt(conversation, Pageable.unpaged())
            .filter { it.role == Role.ASSISTANT }

        assertTrue(assistantMessages.isEmpty())
    }

    @Test
    fun `stream to unknown conversation returns 404`() {
        webTestClient.post()
            .uri("/api/conversations/${UUID.randomUUID()}/messages/stream")
            .header("Authorization", authHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatRequest(message = "Hi"))
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `stream status endpoint reflects redis generating state`() {
        webTestClient.get().uri(statusUri).header("Authorization", authHeader).exchange()
            .expectStatus().isOk
            .expectBody(StreamStatusResponse::class.java)
            .isEqualTo(StreamStatusResponse(generating = false))

        generatingStatusService.markGenerating(conversationId)
        generatingStatusService.updateGeneratingProgress(conversationId, "Hello wor")

        webTestClient.get().uri(statusUri).header("Authorization", authHeader).exchange()
            .expectStatus().isOk
            .expectBody(StreamStatusResponse::class.java)
            .isEqualTo(StreamStatusResponse(generating = true, partial = "Hello wor"))

        generatingStatusService.clearGenerating(conversationId)

        webTestClient.get().uri(statusUri).header("Authorization", authHeader).exchange()
            .expectStatus().isOk
            .expectBody(StreamStatusResponse::class.java)
            .isEqualTo(StreamStatusResponse(generating = false))
    }

    @Test
    fun `stream status reports progress live during generation and clears once it finishes`() {
        every { llmProvider.streamGenerate(any(), any()) } answers {
            val onChunk = secondArg<(String) -> Unit>()
            onChunk("Hello")
            Thread.sleep(700) // exceed the 400ms progress-flush throttle before the next chunk
            onChunk(" world")
        }

        // Fire the request without consuming the body — generation runs off the request thread,
        // so the status endpoint can observe it mid-flight from a separate "connection".
        webTestClient.post()
            .uri(streamUri)
            .header("Authorization", authHeader)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChatRequest(message = "Hi"))
            .exchange()
            .expectStatus().isOk

        Thread.sleep(300)
        val midStatus = webTestClient.get().uri(statusUri).header("Authorization", authHeader).exchange()
            .expectStatus().isOk
            .returnResult(StreamStatusResponse::class.java)
            .responseBody
            .blockFirst(Duration.ofSeconds(5))

        assertEquals(StreamStatusResponse(generating = true, partial = "Hello"), midStatus)

        Thread.sleep(1500)
        val finalStatus = webTestClient.get().uri(statusUri).header("Authorization", authHeader).exchange()
            .expectStatus().isOk
            .returnResult(StreamStatusResponse::class.java)
            .responseBody
            .blockFirst(Duration.ofSeconds(5))

        assertEquals(StreamStatusResponse(generating = false), finalStatus)
    }
}
