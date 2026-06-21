package org.timpeng.chatbot.chat

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ChatController(private val chatService: ChatService) {

    private val logger = LoggerFactory.getLogger(ChatController::class.java)

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): ChatResponse {
        logger.info("[REQ] conversationId=${request.conversationId}, messageLength=${request.message.length}")

        return chatService.chat(request.conversationId, request.message)
    }
}