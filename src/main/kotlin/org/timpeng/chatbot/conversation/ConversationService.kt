package org.timpeng.chatbot.conversation

import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.timpeng.chatbot.chat.ChatResponse
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmResponse
import org.timpeng.chatbot.redis.ConversationCacheService
import java.util.UUID


@Service
class ConversationService (
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val conversationCacheService: ConversationCacheService,
    private val historyService: ConversationHistoryService,
) {

    fun createConversation(ownerId: Long): Conversation {
        return conversationRepository.save(Conversation(uuid = UUID.randomUUID().toString(), ownerId = ownerId))
    }

    // ADR-007: the one ownership gate every conversation-scoped endpoint calls before doing
    // anything else. A conversation that exists but belongs to someone else (or predates auth,
    // ownerId == null) 404s exactly like an unknown id — same reasoning as ConversationNotFoundException
    // elsewhere in this class: don't let a response distinguish "doesn't exist" from "not yours".
    fun requireOwnedConversation(conversationId: String, ownerId: Long): Conversation {
        val conversation = conversationRepository.findByUuid(conversationId)
            .orElseThrow { ConversationNotFoundException("Conversation not found: $conversationId") }
        if (conversation.ownerId != ownerId) {
            throw ConversationNotFoundException("Conversation not found: $conversationId")
        }
        return conversation
    }

    fun saveMessage(conversationId: String, role : Role, content: String): Message {
        val conversation = conversationRepository.findByUuid(conversationId)
            .orElseThrow{ConversationNotFoundException("Conversation not found: $conversationId")}
        val message = Message(conversation = conversation, role = role, content = content)
        messageRepository.save(message)
        cacheAfterCommit(conversationId, message)
        return message
    }

    // Redis 不參與 JPA 交易，若在 commit 前寫入快取，一旦外層交易 rollback，
    // 快取仍會留著一則 DB 沒有的訊息。因此延後到交易確定 commit 後才寫入快取。
    private fun cacheAfterCommit(conversationId: String, message: Message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    conversationCacheService.saveChatMessage(conversationId, message)
                }
            })
        } else {
            conversationCacheService.saveChatMessage(conversationId, message)
        }
    }

    @Transactional
    fun saveUserMessage(conversationId: String, userMsg: String): List<Message> {
        val messages = historyService.getHistory(conversationId).toMutableList()
        messages += saveMessage(conversationId, Role.USER, userMsg)
        return messages
    }

    @Transactional
    fun saveAssistantMessage(conversationId: String, llmResponse: LlmResponse): ChatResponse{
        saveMessage(conversationId, Role.ASSISTANT, llmResponse.message)
        return ChatResponse(llmResponse.message, llmResponse.model, llmResponse.latencyMs)
    }
}