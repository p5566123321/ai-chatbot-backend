package org.timpeng.chatbot.conversation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByUuid(uuid: String): Optional<Conversation>
}