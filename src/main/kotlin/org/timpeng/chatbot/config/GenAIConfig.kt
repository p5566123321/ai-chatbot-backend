package org.timpeng.chatbot.config

import com.google.genai.Client
import com.google.genai.Models
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GenAIConfig {

    @Bean
    fun googleGenAiClient(): Client = Client()

    @Bean
    fun googleGenAiModels(client: Client): Models = client.models
}