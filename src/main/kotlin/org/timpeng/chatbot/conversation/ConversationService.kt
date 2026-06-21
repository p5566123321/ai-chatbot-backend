package org.timpeng.chatbot.conversation

import org.springframework.stereotype.Service
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role

@Service
class ConversationService (private val conversationRepository: ConversationRepository, private val messageRepository: MessageRepository) {
    fun getOrCreateConversation(uuid: String): Conversation {
            return conversationRepository.findByUuid(uuid)
                ?: conversationRepository.save(Conversation(uuid = uuid))
    }

    fun getMessages(conversation: Conversation): List<Message> {
        return messageRepository.findByConversation(conversation)
    }

    fun addMessage(conversation: Conversation, role : Role, message: String) {
        messageRepository.save(Message(conversation = conversation, role = role, content = message))
    }
}