package org.timpeng.chatbot.config

import com.google.genai.Client
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GenAIConfig {

    @Bean
    fun GoogleGenAiClient(): Client {
        return Client()
    }
}