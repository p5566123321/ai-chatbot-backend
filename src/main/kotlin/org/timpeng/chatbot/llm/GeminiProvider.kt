package org.timpeng.chatbot.llm

import com.google.genai.ResponseStream
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.exception.LlmException
import org.timpeng.chatbot.exception.StreamCancelledException
import org.timpeng.chatbot.rag.RagService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

@Service
@ConditionalOnProperty(name = ["app.llm.provider"], havingValue = "gemini")
class GeminiProvider(
    private val geminiClientFactory: GeminiClientFactory,
    private val meterRegistry: MeterRegistry,
    private val ragService: RagService,
    private val userRepository: UserRepository,
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)

    // Fallback when the caller has no per-user GeminiSettings.model override — see
    // buildGenerationConfig/effectiveModel below.
    @Value("\${app.llm.gemini.model}")
    private val defaultModel = "gemini-3-flash-preview"

    @Value("\${app.llm.gemini.timeout-ms}")
    private val timeoutMs = 30000L

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

    override fun generate(
        messages: List<Message>,
        ownerId: Long,
    ): LlmResponse {
        val user = userRepository.findById(ownerId).orElse(null)
        val contents = buildContents(messages, ownerId)
        val config = buildGenerationConfig(user)
        val effectiveModel = user?.geminiSettings?.model ?: defaultModel
        val models = geminiClientFactory.modelsFor(user)

        var outcome = "success"
        val sample = Timer.start(meterRegistry)
        try {
            val (response, duration) = measureTimedValue {
                callWithTimeout {
                    models.generateContent(
                        effectiveModel,
                        contents,
                        config
                    )
                }
            }

            val usage = response.usageMetadata().orElse(null)
            val promptTokens = usage?.promptTokenCount()?.orElse(null)
            val candidatesTokens = usage?.candidatesTokenCount()?.orElse(null)
            val totalTokens = usage?.totalTokenCount()?.orElse(null)

            logger.info(
                "[Gemini API] model={}, latency={}, promptTokens={}, candidatesTokens={}, totalTokens={}",
                effectiveModel,
                duration.inWholeMilliseconds,
                promptTokens,
                candidatesTokens,
                totalTokens,
            )

            return LlmResponse(
                response.text().toString(),
                effectiveModel,
                duration.inWholeMilliseconds
            )
        } catch (e: TimeoutException) {
            outcome = "timeout"
            logger.error("Gemini generate timed out after ${timeoutMs}ms", e)
            throw LlmException("[Gemini API] request timed out after ${timeoutMs}ms", e)
        } catch (e: java.lang.Exception) {
            outcome = "failure"
            logger.error("Gemini generate failed", e)
            throw LlmException("[Gemini API] unavailable", e)
        } finally {
            sample.stop(
                Timer.builder("llm.generate.time")
                    .description("LLM API 呼叫耗時")
                    .tag("provider", "gemini")
                    .tag("model", effectiveModel)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
        }
    }

    override fun streamGenerate(messagesWithUser: List<Message>, ownerId: Long, onChunk: (String) -> Unit) {
        val user = userRepository.findById(ownerId).orElse(null)
        val contents = buildContents(messagesWithUser, ownerId)
        val config = buildGenerationConfig(user)
        val effectiveModel = user?.geminiSettings?.model ?: defaultModel
        val models = geminiClientFactory.modelsFor(user)

        var outcome = "success"
        val sample = Timer.start(meterRegistry)
        val start = System.nanoTime()
        var firstTokenLatencyMs: Long? = null
        val responseRef = AtomicReference<ResponseStream<GenerateContentResponse>?>()

        try {
            callWithTimeout {
                val stream = models.generateContentStream(
                    effectiveModel,
                    contents,
                    config
                )
                responseRef.set(stream)

                for (chunk in stream) {
                    if (firstTokenLatencyMs == null) {
                        firstTokenLatencyMs = (System.nanoTime() - start) / 1_000_000
                    }
                    chunk.text()?.let { onChunk(it) }
                }
            }

            val totalLatencyMs = (System.nanoTime() - start) / 1_000_000
            logger.info(
                "[Gemini API] model={}, firstTokenLatency={}ms, totalLatency={}ms",
                effectiveModel,
                firstTokenLatencyMs,
                totalLatencyMs,
            )
        } catch (e: StreamCancelledException) {
            outcome = "cancelled"
            throw e
        } catch (e: TimeoutException) {
            outcome = "timeout"
            logger.error("Gemini streamGenerate timed out after ${timeoutMs}ms", e)
            throw LlmException("[Gemini API] request timed out after ${timeoutMs}ms", e)
        } catch (e: java.lang.Exception) {
            outcome = "failure"
            logger.error("Gemini streamGenerate failed", e)
            throw LlmException("[Gemini API] unavailable", e)
        } finally {
            responseRef.get()?.close()
            sample.stop(
                Timer.builder("llm.stream_generate.time")
                    .description("LLM 串流總耗時")
                    .tag("provider", "gemini")
                    .tag("model", effectiveModel)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
            firstTokenLatencyMs?.let {
                Timer.builder("llm.stream_generate.first_token_time")
                    .description("LLM 串流首個 token 延遲")
                    .tag("provider", "gemini")
                    .tag("model", effectiveModel)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(it, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Per-user `GenerateContentConfig` overrides (`GeminiSettings`, `V7__add_user_gemini_settings.sql`),
     * toggled via `PATCH /api/users/me/gemini-settings`. Takes the already-fetched [User] rather
     * than re-querying by ownerId — [generate]/[streamGenerate] fetch it once and reuse it for this,
     * the effective model, and [GeminiClientFactory]. `null` (not an empty builder) when the caller
     * has no overrides set at all, matching this class's pre-existing behavior of passing `null` for
     * config — Gemini's own defaults apply exactly as before this feature existed. Note `model`
     * lives on `GeminiSettings` too but is applied separately (it's not a `GenerateContentConfig`
     * field) — see the `effectiveModel` computation in [generate]/[streamGenerate].
     *
     * `candidateCount` is threaded through as requested, but note [generate] only ever reads
     * `response.text()` (the first candidate) — this doesn't yet surface additional candidates
     * anywhere in `LlmResponse`/the SSE stream.
     */
    private fun buildGenerationConfig(user: User?): GenerateContentConfig? {
        val settings = user?.geminiSettings ?: return null
        if (settings.isGenerationConfigEmpty()) return null

        val builder = GenerateContentConfig.builder()
        settings.systemInstruction?.let { builder.systemInstruction(Content.fromParts(Part.fromText(it))) }
        settings.temperature?.let { builder.temperature(it) }
        settings.topP?.let { builder.topP(it) }
        settings.topK?.let { builder.topK(it) }
        settings.candidateCount?.let { builder.candidateCount(it) }
        settings.maxOutputTokens?.let { builder.maxOutputTokens(it) }
        return builder.build()
    }

    /**
     * `generate`/`streamGenerate` are non-suspend (see [LlmProvider]), but [RagService.buildPrompt]
     * is suspend (it calls the suspend `EmbeddingProvider.embed`) — bridged with [runBlocking]
     * rather than propagating suspend through the whole `LlmProvider` call chain, consistent with
     * this class already being fully blocking (`callWithTimeout` uses `CompletableFuture.get`).
     */
    private fun buildContents(messages: List<Message>, ownerId: Long): List<Content> {
        val contents = mutableListOf<Content>()

        for ((index, message) in messages.withIndex()) {
            if (message.role != Role.USER && message.role != Role.ASSISTANT) continue

            // Only the final user turn gets RAG-augmented — earlier turns are sent verbatim so the
            // model still sees the real conversation history.

            val isFinalUserTurn = index == messages.lastIndex && message.role == Role.USER
            val text = if (isFinalUserTurn) {
                runBlocking { ragService.buildPrompt(message.content, ownerId) }
            } else {
                message.content
            }

            contents.add(
                Content.builder()
                    .role(message.role.geminiName)
                    .parts(listOf(Part.fromText(text)))
                    .build()
            )
        }

        return contents
    }
}