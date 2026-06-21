package org.timpeng.chatbot.llm

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration(proxyBeanMethods = false)
class LlmConfig(
    @Value("\${app.llm.provider}") private val providerName: String,
    @Autowired(required = false) private val geminiProvider: GeminiProvider? = null
) {
    @Bean
    @Primary
    fun llmProvider(): LlmProvider = when (providerName) {
        "gemini" -> geminiProvider ?: throw IllegalStateException("GeminiProvider bean not found")
        else -> throw IllegalArgumentException("Unknown provider: $providerName")
    }

}