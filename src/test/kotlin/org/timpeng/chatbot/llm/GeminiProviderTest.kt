package org.timpeng.chatbot.llm

import com.google.genai.Models
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.auth.user.GeminiSettings
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.LlmException
import org.timpeng.chatbot.rag.RagService
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiProviderTest {

    private val models: Models = mockk(relaxed = true)
    private lateinit var geminiProvider: GeminiProvider
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry()
    private val ragService: RagService = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk()
    private val geminiClientFactory: GeminiClientFactory = mockk()

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")
    private val ownerId = 1L

    // Empty GeminiSettings (the User default) makes buildGenerationConfig return null — every
    // existing test below asserts a literal `null` third arg, unchanged from before this feature
    // existed, so this is the baseline every test gets unless it stubs geminiSettings itself.
    private fun userWithSettings(settings: GeminiSettings = GeminiSettings()) = User(
        id = ownerId,
        email = "user@example.com",
        passwordHash = "hashed",
        geminiSettings = settings,
    )

    @BeforeEach
    fun setUp() {
        every { userRepository.findById(ownerId) } returns Optional.of(userWithSettings())
        // Every existing test below exercises the "no BYOK key" path — modelsFor always hands back
        // the same mocked `models`, regardless of which User it's called with.
        every { geminiClientFactory.modelsFor(any()) } returns models
        geminiProvider = GeminiProvider(geminiClientFactory, meterRegistry, ragService, userRepository)
    }

    private fun stubGenerate(replyText: String): GenerateContentResponse {
        val response: GenerateContentResponse = mockk(relaxed = true)
        every { response.text() } returns replyText
        every { response.usageMetadata() } returns Optional.empty()
        every { models.generateContent(any<String>(), any<List<Content>>(), null) } returns response
        return response
    }

    @Test
    fun `generate returns LlmResponse with text and model name`() {
        stubGenerate("Hello!")

        val result = geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        assertEquals("Hello!", result.message)
        assertEquals("gemini-3-flash-preview", result.model)
    }

    @Test
    fun `generate returns non-negative latency`() {
        stubGenerate("Hi!")

        val result = geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hello")
        ), ownerId)

        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `generate skips system role messages`() {
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.SYSTEM, content = "You are a helpful assistant"),
            Message(conversation = conversation, role = Role.USER, content = "Hello")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                match<List<Content>> { it.size == 1 },
                null
            )
        }
    }

    @Test
    fun `generate maps user and assistant messages with correct gemini role names`() {
        stubGenerate("I'm fine")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hello"),
            Message(conversation = conversation, role = Role.ASSISTANT, content = "Hi!"),
            Message(conversation = conversation, role = Role.USER, content = "How are you?")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                match<List<Content>> { contents ->
                    contents.size == 3 &&
                    contents[0].role().orElse("") == "user" &&
                    contents[1].role().orElse("") == "model" &&
                    contents[2].role().orElse("") == "user"
                },
                null
            )
        }
    }

    @Test
    fun `generate augments only the final user message with the RAG prompt`() {
        stubGenerate("answer")
        coEvery { ragService.buildPrompt("How are you?", ownerId) } returns "RAG-AUGMENTED: How are you?"

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hello"),
            Message(conversation = conversation, role = Role.ASSISTANT, content = "Hi!"),
            Message(conversation = conversation, role = Role.USER, content = "How are you?")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                match<List<Content>> { contents ->
                    contents.size == 3 &&
                    contents[0].textOf() == "Hello" &&
                    contents[1].textOf() == "Hi!" &&
                    contents[2].textOf() == "RAG-AUGMENTED: How are you?"
                },
                null
            )
        }
    }

    @Test
    fun `generate does not duplicate any message`() {
        stubGenerate("answer")
        coEvery { ragService.buildPrompt(any(), ownerId) } returns "augmented"

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hello"),
            Message(conversation = conversation, role = Role.ASSISTANT, content = "Hi!"),
            Message(conversation = conversation, role = Role.USER, content = "How are you?")
        ), ownerId)

        // Regression test: buildContents used to run two loops over `messages`, appending
        // ASSISTANT turns and the final USER turn a second (or third) time.
        verify {
            models.generateContent(any(), match<List<Content>> { it.size == 3 }, null)
        }
    }

    @Test
    fun `generate sends messages to the configured model`() {
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { models.generateContent("gemini-3-flash-preview", any<List<Content>>(), null) }
    }

    @Test
    fun `generate throws LlmException when models throws`() {
        every { models.generateContent(any<String>(), any<List<Content>>(), null) } throws RuntimeException("API error")

        assertThrows<LlmException> {
            geminiProvider.generate(listOf(
                Message(conversation = conversation, role = Role.USER, content = "Hi")
            ), ownerId)
        }
    }

    @Test
    fun `generate preserves the original exception as cause`() {
        val originalError = RuntimeException("quota exceeded")
        every { models.generateContent(any<String>(), any<List<Content>>(), null) } throws originalError

        val thrown = assertThrows<LlmException> {
            geminiProvider.generate(listOf(
                Message(conversation = conversation, role = Role.USER, content = "Hi")
            ), ownerId)
        }

        assertEquals(originalError, thrown.cause)
    }

    @Test
    fun `generate with only system messages sends empty contents`() {
        stubGenerate("Hello!")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.SYSTEM, content = "System prompt only")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                match<List<Content>> { it.isEmpty() },
                null
            )
        }
    }

    // buildGenerationConfig (per-user GenerateContentConfig overrides)

    @Test
    fun `generate passes null config when the caller has no owner row`() {
        every { userRepository.findById(ownerId) } returns Optional.empty()
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { models.generateContent(any(), any<List<Content>>(), null) }
    }

    @Test
    fun `generate builds a GenerateContentConfig from the caller's gemini settings`() {
        every { userRepository.findById(ownerId) } returns Optional.of(
            userWithSettings(
                GeminiSettings(
                    systemInstruction = "Be concise",
                    temperature = 0.7f,
                    topP = 0.9f,
                    topK = 40f,
                    candidateCount = 2,
                    maxOutputTokens = 2048,
                )
            )
        )
        val response: GenerateContentResponse = mockk(relaxed = true)
        every { response.text() } returns "Response"
        every { response.usageMetadata() } returns Optional.empty()
        every { models.generateContent(any<String>(), any<List<Content>>(), any<GenerateContentConfig>()) } returns response

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                any<List<Content>>(),
                match<GenerateContentConfig> { config ->
                    config.systemInstruction().get().parts().get()[0].text().get() == "Be concise" &&
                        config.temperature().get() == 0.7f &&
                        config.topP().get() == 0.9f &&
                        config.topK().get() == 40f &&
                        config.candidateCount().get() == 2 &&
                        config.maxOutputTokens().get() == 2048
                }
            )
        }
    }

    @Test
    fun `generate omits unset fields from the built config`() {
        every { userRepository.findById(ownerId) } returns Optional.of(
            userWithSettings(GeminiSettings(temperature = 0.5f))
        )
        val response: GenerateContentResponse = mockk(relaxed = true)
        every { response.text() } returns "Response"
        every { response.usageMetadata() } returns Optional.empty()
        every { models.generateContent(any<String>(), any<List<Content>>(), any<GenerateContentConfig>()) } returns response

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify {
            models.generateContent(
                any(),
                any<List<Content>>(),
                match<GenerateContentConfig> { config ->
                    config.temperature().get() == 0.5f && config.topP().isEmpty
                }
            )
        }
    }

    @Test
    fun `generate passes null config when the caller only overrode model`() {
        every { userRepository.findById(ownerId) } returns Optional.of(
            userWithSettings(GeminiSettings(model = "gemini-2.5-pro"))
        )
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { models.generateContent("gemini-2.5-pro", any<List<Content>>(), null) }
    }

    // Model choice + BYOK (ADR-010)

    @Test
    fun `generate uses the caller's model override instead of the configured default`() {
        every { userRepository.findById(ownerId) } returns Optional.of(
            userWithSettings(GeminiSettings(model = "gemini-2.5-flash"))
        )
        stubGenerate("Response")

        val result = geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        assertEquals("gemini-2.5-flash", result.model)
        verify { models.generateContent("gemini-2.5-flash", any<List<Content>>(), null) }
    }

    @Test
    fun `generate falls back to the configured default model when there is no override`() {
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { models.generateContent("gemini-3-flash-preview", any<List<Content>>(), null) }
    }

    @Test
    fun `generate resolves Models through GeminiClientFactory using the fetched user`() {
        val user = userWithSettings()
        every { userRepository.findById(ownerId) } returns Optional.of(user)
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { geminiClientFactory.modelsFor(user) }
    }

    @Test
    fun `generate uses the BYOK client returned by GeminiClientFactory when the user has an api key`() {
        val user = userWithSettings().copy(geminiApiKeyCiphertext = "ciphertext")
        val byokModels: Models = mockk()
        every { userRepository.findById(ownerId) } returns Optional.of(user)
        every { geminiClientFactory.modelsFor(user) } returns byokModels
        val response: GenerateContentResponse = mockk(relaxed = true)
        every { response.text() } returns "Response"
        every { response.usageMetadata() } returns Optional.empty()
        every { byokModels.generateContent(any<String>(), any<List<Content>>(), null) } returns response

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ), ownerId)

        verify { byokModels.generateContent(any<String>(), any<List<Content>>(), null) }
        verify(exactly = 0) { models.generateContent(any<String>(), any<List<Content>>(), any()) }
    }

    private fun Content.textOf(): String = parts().get()[0].text().get()
}
