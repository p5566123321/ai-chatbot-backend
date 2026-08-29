package org.timpeng.chatbot.rag.embedding

import com.google.genai.Models
import com.google.genai.types.EmbedContentConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.timpeng.chatbot.exception.LlmException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Deliberately NOT @Service/component-scanned, and deliberately NOT gated by a class-level
// @ConditionalOnBean(Models::class) — Spring Boot's own docs warn @ConditionalOnBean on a
// @Component is unreliable (bean-registration-order dependent: it can evaluate before GenAIConfig's
// Models bean has been registered, letting this class get instantiated anyway and then fail to
// autowire a required non-null Models — this is exactly what happened when it was tried that way,
// see docs/decision/011). Instead GenAIConfig.geminiEmbeddingProvider wires this up explicitly as a
// @Bean factory method, where the null-propagation trick (no Models -> no bean) is reliable.
class GeminiEmbeddingProvider(
    private val models: Models,
    private val meterRegistry: MeterRegistry
) : EmbeddingProvider {

    private val logger = LoggerFactory.getLogger(GeminiEmbeddingProvider::class.java)

    @Value("\${app.llm.gemini.embedding-model}")
    private val model = "text-embedding-004"

    @Value("\${app.llm.gemini.embedding-timeout-ms}")
    private val timeoutMs = 30000L

    // Must match document_chunk.embedding's vector(N) column — see application.yaml's comment on
    // this property for why this is passed on every call rather than left to the model's default.
    @Value("\${app.llm.gemini.embedding-dimensions}")
    private val outputDimensions = 768

    private fun <T> callWithTimeout(block: () -> T): T {
        val future = CompletableFuture.supplyAsync(block)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    override suspend fun embed(text: String): FloatArray {
        var outcome = "success"
        val sample = Timer.start(meterRegistry)
        try {
            val config = EmbedContentConfig.builder()
                .outputDimensionality(outputDimensions)
                .build()
            val response = callWithTimeout {
                models.embedContent(model, text, config)
            }
            return response.embeddings().get()[0].values().get().toFloatArray()
        } catch (e: TimeoutException) {
            outcome = "timeout"
            logger.error("Gemini embed timed out after ${timeoutMs}ms", e)
            throw LlmException("[Gemini API] embedding request timed out after ${timeoutMs}ms", e)
        } catch (e: Exception) {
            outcome = "failure"
            logger.error("Gemini embed failed", e)
            throw LlmException("[Gemini API] embedding unavailable", e)
        } finally {
            sample.stop(
                Timer.builder("llm.embed.time")
                    .description("Embedding API 呼叫耗時")
                    .tag("provider", "gemini")
                    .tag("model", model)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
        }
    }
}