package org.timpeng.chatbot.llm

import com.google.genai.Models
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import kotlin.time.measureTimedValue

@Service
@ConditionalOnProperty(name = ["app.llm.provider"], havingValue = "gemini")
class GeminiProvider(
    private val models: Models,
    private val meterRegistry: MeterRegistry,
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)

    @Value("\${app.llm.gemini.model}")
    private val model = "gemini-3-flash-preview"

    override fun generate(
        messages: List<Message>
    ): LlmResponse {
        val contents = mutableListOf<Content>()

        for (message in messages) {
            if (message.role == Role.USER || message.role == Role.ASSISTANT) {
                contents.add(
                    Content.builder()
                        .role(message.role.geminiName)
                        .parts(listOf(Part.fromText(message.content)))
                        .build()
                )
            }
        }

        var outcome = "success"
        val sample = Timer.start(meterRegistry)
        try {
            val (response, duration) = measureTimedValue {
                models.generateContent(
                    model,
                    contents,
                    null
                )
            }

            val usage = response.usageMetadata().orElse(null)
            val promptTokens = usage?.promptTokenCount()?.orElse(null)
            val candidatesTokens = usage?.candidatesTokenCount()?.orElse(null)
            val totalTokens = usage?.totalTokenCount()?.orElse(null)

            logger.info(
                "[Gemini API] model={}, latency={}, promptTokens={}, candidatesTokens={}, totalTokens={}",
                model,
                duration.inWholeMilliseconds,
                promptTokens,
                candidatesTokens,
                totalTokens,
            )

            return LlmResponse(
                response.text().toString(),
                model,
                duration.inWholeMilliseconds
            )
        } catch (_: java.lang.Exception) {
            outcome = "failure"
            throw LlmException("[Gemini API] unavailable")
        } finally {
            sample.stop(
                Timer.builder("llm.generate.time")
                    .description("LLM API 呼叫耗時")
                    .tag("provider", "gemini")
                    .tag("model", model)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
        }
    }
}