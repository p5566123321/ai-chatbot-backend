package org.timpeng.chatbot.llm

import com.google.genai.Models
import com.google.genai.types.Content
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

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")
    private val ownerId = 1L

    @BeforeEach
    fun setUp() {
        geminiProvider = GeminiProvider(models, meterRegistry, ragService)
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

    private fun Content.textOf(): String = parts().get()[0].text().get()
}
