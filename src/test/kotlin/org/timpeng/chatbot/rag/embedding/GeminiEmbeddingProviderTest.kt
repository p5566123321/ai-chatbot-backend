package org.timpeng.chatbot.rag.embedding

import com.google.genai.Models
import com.google.genai.types.ContentEmbedding
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.EmbedContentResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.exception.LlmException
import java.util.Optional
import kotlin.test.assertEquals

class GeminiEmbeddingProviderTest {

    private val models: Models = mockk(relaxed = true)
    private lateinit var provider: GeminiEmbeddingProvider
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        provider = GeminiEmbeddingProvider(models, meterRegistry)
    }

    private fun stubEmbed(values: List<Float>) {
        val embedding: ContentEmbedding = mockk(relaxed = true)
        every { embedding.values() } returns Optional.of(values)
        val response: EmbedContentResponse = mockk(relaxed = true)
        every { response.embeddings() } returns Optional.of(listOf(embedding))
        every { models.embedContent(any<String>(), any<String>(), any<EmbedContentConfig>()) } returns response
    }

    @Test
    fun `embed returns the embedding vector`() = runBlocking {
        stubEmbed(listOf(0.1f, 0.2f, 0.3f))

        val result = provider.embed("hello world")

        assertEquals(listOf(0.1f, 0.2f, 0.3f), result.toList())
    }

    @Test
    fun `embed calls the API with the configured model, input text, and output dimensionality`() = runBlocking {
        stubEmbed(listOf(0.1f))

        provider.embed("hello")

        verify {
            models.embedContent(
                "text-embedding-004",
                "hello",
                match<EmbedContentConfig> { it.outputDimensionality().get() == 768 },
            )
        }
    }

    @Test
    fun `embed throws LlmException when the API call fails`() {
        every { models.embedContent(any<String>(), any<String>(), any<EmbedContentConfig>()) } throws RuntimeException("quota exceeded")

        assertThrows<LlmException> {
            runBlocking { provider.embed("hello") }
        }
    }
}
