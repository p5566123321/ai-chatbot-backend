package org.timpeng.chatbot.chat

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.auth.CurrentUserId

@RestController
@RequestMapping("/api/conversations/{conversationId}")
class ChatController(private val chatService: ChatService) {

    private val logger = LoggerFactory.getLogger(ChatController::class.java)

    @Value("\${app.sse.timeout-ms}")
    private val sseTimeoutMs: Long = 60000

    @PostMapping("/messages")
    fun chat(
        @PathVariable conversationId: String,
        @CurrentUserId ownerId: Long,
        @RequestBody request: ChatRequest,
    ): ChatResponse {
        logger.info("[REQ] conversationId=$conversationId, messageLength=${request.message.length}")

        return chatService.chat(conversationId, ownerId, request.message)
    }

    @PostMapping("/messages/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamChat(
        @PathVariable conversationId: String,
        @CurrentUserId ownerId: Long,
        @RequestBody request: ChatRequest,
    ): SseEmitter {
        logger.info("[REQ] conversationId=$conversationId, messageLength=${request.message.length}")

        val emitter = SseEmitter(sseTimeoutMs)
        chatService.streamChat(conversationId, ownerId, request.message, emitter)
        return emitter
    }

    @GetMapping("/messages/stream/status")
    fun streamStatus(@PathVariable conversationId: String, @CurrentUserId ownerId: Long): StreamStatusResponse {
        return chatService.streamStatus(conversationId, ownerId)
    }
}