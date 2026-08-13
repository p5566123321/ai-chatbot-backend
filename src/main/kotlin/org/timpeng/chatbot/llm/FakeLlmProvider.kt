package org.timpeng.chatbot.llm

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.exception.StreamCancelledException
import java.util.concurrent.TimeUnit

private val FAKE_CHUNKS = listOf(
    "This ", "is ", "a ", "fake ", "LLM ", "response ", "used ", "for ", "benchmarking."
)

/**
 * Deterministic, no-external-call [LlmProvider] for load-testing the queue pipeline in isolation
 * from real LLM latency. `k6-history-latency.js` deliberately never calls the chat endpoints for
 * exactly this reason (multi-second Gemini latency drowns out the signal being measured) — this
 * provider exists so a future queue-focused k6 run can hit `POST .../messages/stream` for real
 * and still get a clean read on queue overhead specifically, not Gemini's.
 *
 * Activate with `app.llm.provider=fake` (env `LLM_PROVIDER=fake`). Publishes the same
 * `llm.generate.time` / `llm.stream_generate.time` / `llm.stream_generate.first_token_time`
 * metrics as [GeminiProvider] (tagged `provider=fake`), so existing dashboards/queries built
 * around those metric names work unchanged against a benchmark run using this provider.
 */
@Service
@ConditionalOnProperty(name = ["app.llm.provider"], havingValue = "fake")
class FakeLlmProvider(
    private val meterRegistry: MeterRegistry,
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(FakeLlmProvider::class.java)

    // Small non-zero delay per chunk by default rather than instant: a literally-0ms fake still
    // exercises queue mechanics, but pacing chunks a little (a) keeps the llm.*.time histograms
    // out of a single degenerate bucket and (b) gives ChatJobHandler's progress-flush throttling
    // (PROGRESS_FLUSH_INTERVAL_MS) something real to do, matching production shape more closely.
    @Value("\${app.llm.fake.chunk-delay-ms}")
    private val chunkDelayMs = 20L

    override fun generate(messages: List<Message>): LlmResponse {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            val latencyMs = chunkDelayMs * FAKE_CHUNKS.size
            if (latencyMs > 0) Thread.sleep(latencyMs)
            val message = FAKE_CHUNKS.joinToString("")
            logger.info("[Fake LLM] generate returning {} chars after {}ms", message.length, latencyMs)
            return LlmResponse(message, "fake", latencyMs)
        } catch (e: Exception) {
            outcome = "failure"
            throw e
        } finally {
            sample.stop(
                Timer.builder("llm.generate.time")
                    .description("LLM API 呼叫耗時")
                    .tag("provider", "fake")
                    .tag("model", "fake")
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
        }
    }

    override fun streamGenerate(messagesWithUser: List<Message>, onChunk: (String) -> Unit) {
        val sample = Timer.start(meterRegistry)
        val start = System.nanoTime()
        var firstTokenLatencyMs: Long? = null
        var outcome = "success"
        try {
            for (chunk in FAKE_CHUNKS) {
                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                if (firstTokenLatencyMs == null) firstTokenLatencyMs = (System.nanoTime() - start) / 1_000_000
                onChunk(chunk) // propagates StreamCancelledException from here same as GeminiProvider
            }
        } catch (e: StreamCancelledException) {
            outcome = "cancelled"
            throw e
        } catch (e: Exception) {
            outcome = "failure"
            throw e
        } finally {
            sample.stop(
                Timer.builder("llm.stream_generate.time")
                    .description("LLM 串流總耗時")
                    .tag("provider", "fake")
                    .tag("model", "fake")
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
            firstTokenLatencyMs?.let {
                Timer.builder("llm.stream_generate.first_token_time")
                    .description("LLM 串流首個 token 延遲")
                    .tag("provider", "fake")
                    .tag("model", "fake")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(it, TimeUnit.MILLISECONDS)
            }
        }
    }
}
