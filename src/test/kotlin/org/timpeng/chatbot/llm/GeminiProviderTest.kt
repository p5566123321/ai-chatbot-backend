package org.timpeng.chatbot.llm

import com.google.genai.Models
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiProviderTest {

    private val models: Models = mockk(relaxed = true)
    private lateinit var geminiProvider: GeminiProvider

    private val conversation = Conversation(id = 1L, uuid = "test-uuid")

    @BeforeEach
    fun setUp() {
        geminiProvider = GeminiProvider(models)
    }

    private fun stubGenerate(replyText: String): GenerateContentResponse {
        val response: GenerateContentResponse = mockk(relaxed = true)
        every { response.text() } returns replyText
        every { models.generateContent(any<String>(), any<List<Content>>(), null) } returns response
        return response
    }

    @Test
    fun `generate returns LlmResponse with text and model name`() {
        stubGenerate("Hello!")

        val result = geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ))

        assertEquals("Hello!", result.message)
        assertEquals("gemini-3-flash-preview", result.model)
    }

    @Test
    fun `generate returns non-negative latency`() {
        stubGenerate("Hi!")

        val result = geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hello")
        ))

        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `generate skips system role messages`() {
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.SYSTEM, content = "You are a helpful assistant"),
            Message(conversation = conversation, role = Role.USER, content = "Hello")
        ))

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
        ))

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
    fun `generate sends messages to the configured model`() {
        stubGenerate("Response")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.USER, content = "Hi")
        ))

        verify { models.generateContent("gemini-3-flash-preview", any<List<Content>>(), null) }
    }

    @Test
    fun `generate throws LlmException when models throws`() {
        every { models.generateContent(any<String>(), any<List<Content>>(), null) } throws RuntimeException("API error")

        assertThrows<LlmException> {
            geminiProvider.generate(listOf(
                Message(conversation = conversation, role = Role.USER, content = "Hi")
            ))
        }
    }

    @Test
    fun `generate with only system messages sends empty contents`() {
        stubGenerate("Hello!")

        geminiProvider.generate(listOf(
            Message(conversation = conversation, role = Role.SYSTEM, content = "System prompt only")
        ))

        verify {
            models.generateContent(
                any(),
                match<List<Content>> { it.isEmpty() },
                null
            )
        }
    }
}
