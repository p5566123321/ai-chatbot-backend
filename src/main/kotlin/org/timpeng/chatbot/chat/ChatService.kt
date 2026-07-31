package org.timpeng.chatbot.chat

import io.micrometer.core.annotation.Timed
import io.micrometer.core.instrument.MeterRegistry
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.llm.LlmResponse

@Service
class ChatService(
    private val conversationService: ConversationService,
    private val llmProvider: LlmProvider
) {

    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    @Timed(value = "total.chat.time", description = "聊天耗時", percentiles = [0.5, 0.95, 0.99])
    fun chat(conversationId: String, userMsg: String): ChatResponse {

        val messages = conversationService.saveUserMessage(conversationId, userMsg)

        val llmResponse = runCatching {
            llmProvider.generate(messages)
        }.getOrElse { e ->
            logger.error("LLM API failed", e)
            throw ChatException("AI service unable to response.", e)
        }

        return conversationService.saveAssistantMessage(conversationId, llmResponse)
    }
}