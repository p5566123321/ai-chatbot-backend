package org.timpeng.chatbot.chat

import jakarta.transaction.Transactional
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
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
    private val markdownParser = Parser.builder().build()
    private val htmlRenderer = HtmlRenderer.builder().build()

    @Transactional
    fun chat(conversationId: String, userMsg: String): ChatResponse {
        val conversation = conversationService.getOrCreateConversation(conversationId)

        conversationService.addMessage(conversation, Role.USER, userMsg)

        val messages = conversationService.getMessages(conversation)

        val llmResponse = runCatching {
            llmProvider.generate(messages)
        }.getOrElse { e ->
            logger.error("LLM API failed", e)
            throw ChatException("AI service unable to response.", e)
        }
        conversationService.addMessage(conversation, Role.ASSISTANT, llmResponse.message)

        return ChatResponse(parse2Html(llmResponse.message), llmResponse.model, llmResponse.latencyMs)
    }

    private fun parse2Html(markdown: String): String {
        val document = markdownParser.parse(markdown)
        return htmlRenderer.render(document)
    }
}