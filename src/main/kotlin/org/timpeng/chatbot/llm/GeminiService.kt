package org.timpeng.chatbot.llm

import com.google.genai.Client
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.time.measureTimedValue

@Service
class GeminiService(private val client: Client) {

    private val logger = LoggerFactory.getLogger(GeminiService::class.java)

    fun ask(prompt: String): String{
        val (response, duration) = measureTimedValue { client.models.generateContent("gemini-3-flash-preview",
            prompt,
            null)
        }

        logger.info("[LLM] response_time=${duration}s")
        return response.text().toString()
    }

}