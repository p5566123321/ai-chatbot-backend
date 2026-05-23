package org.timpeng.chatbot.chat

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.timpeng.chatbot.llm.GeminiService

@RestController
class ChatController(private val geminiService: GeminiService) {

    private val logger = LoggerFactory.getLogger(ChatController::class.java)

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): String {
        logger.info("[REQ] msg=${request.message}")

        return geminiService.ask(request.message)
    }
}