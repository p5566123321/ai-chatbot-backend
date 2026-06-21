package org.timpeng.chatbot.conversation

import org.springframework.data.jpa.repository.JpaRepository

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByUuid(uuid: String): Conversation?
}