package org.timpeng.chatbot.chat

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider

@Service
class ChatService(
    private val conversationService: ConversationService,
    private val llmProvider: LlmProvider
) {

    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    @Transactional
    fun chat(conversationId: String, userMsg: String): ChatResponse {

        var messages = conversationService.getHistory(conversationId)

        messages += conversationService.saveMessage(conversationId, Role.USER, userMsg)

        val llmResponse = runCatching {
            llmProvider.generate(messages)
        }.getOrElse { e ->
            logger.error("LLM API failed", e)
            throw ChatException("AI service unable to response.", e)
        }

        conversationService.saveMessage(conversationId, Role.ASSISTANT, llmResponse.message)

        return ChatResponse(llmResponse.message, llmResponse.model, llmResponse.latencyMs)
    }
}