package org.timpeng.chatbot.conversation.message

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.timpeng.chatbot.conversation.Conversation

interface MessageRepository : JpaRepository<Message, Long> {

    fun findByConversationOrderByCreatedAt(conversation: Conversation, pageable: Pageable): List<Message>
}