package org.timpeng.chatbot.conversation

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.timpeng.chatbot.conversation.message.MessageResponse
import java.net.URI

@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val conversationService: ConversationService,
    private val conversationHistoryService: ConversationHistoryService,
) {

    @PostMapping
    fun createConversation(): ResponseEntity<ConversationResponse> {
        val conversation = conversationService.createConversation()
        return ResponseEntity
            .created(URI.create("/api/conversations/${conversation.uuid}"))
            .body(ConversationResponse(conversation.uuid, conversation.createdAt))
    }

    @GetMapping("/{conversationId}/messages")
    fun getMessages(@PathVariable conversationId: String): List<MessageResponse> {
        return conversationHistoryService.getHistory(conversationId)
            .map { MessageResponse(it.role, it.content, it.createdAt) }
    }
}
