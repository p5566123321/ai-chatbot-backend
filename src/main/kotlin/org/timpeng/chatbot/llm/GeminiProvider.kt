package org.timpeng.chatbot.llm

import com.google.genai.Models
import com.google.genai.types.Content
import com.google.genai.types.Part
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import kotlin.time.measureTimedValue

@Service
@ConditionalOnProperty(name = ["app.llm.provider"], havingValue = "gemini")
class GeminiProvider(
    private val models: Models,
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)

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

        try {
            val (response, duration) = measureTimedValue {
                models.generateContent(
                    model,
                    contents,
                    null
                )
            }

            logger.info(
                "model={}, latency={}, tokenUsage={}",
                model,
                duration.inWholeMilliseconds,
                response.usageMetadata()
            )

            return LlmResponse(
                response.text().toString(),
                model,
                duration.inWholeMilliseconds
            )
        } catch (_: java.lang.Exception) {
            throw LlmException("Gemini unavailable")
        }
    }
}