package org.timpeng.chatbot.conversation

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.MessageRepository
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.redis.RedisService


@Service
class ConversationService (
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val redisService: RedisService,
) {

    @Value($$"${app.conversation.cache.max-msg}")
    private val maxMessages = 10

    private val logger = LoggerFactory.getLogger(ConversationService::class.java)


    fun getHistory(conversationId : String): List<Message> {
        var messages = redisService.getChatHistory(conversationId)
        if(messages.isEmpty()){
            val conversation = conversationRepository.findByUuid(conversationId)
                .orElseGet {
                    logger.info("[GetHistory] no conversation with id=$conversationId, create new conversation.")
                    conversationRepository.save(
                        Conversation(
                            uuid = conversationId
                        )
                    )
                }
            val pageable: Pageable = PageRequest.of(0, maxMessages)

            messages = messageRepository.findByConversationOrderByCreatedAtDesc(conversation, pageable).reversed()

            for( message in messages){
                redisService.saveChatMessage(conversationId, message)
            }
        }
        return messages
    }

    fun saveMessage(conversationId: String, role : Role, content: String): Message {
        val conversation = conversationRepository.findByUuid(conversationId)
            .orElseThrow{NoSuchElementException("Conversation not found: $conversationId")}
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
                    redisService.saveChatMessage(conversationId, message)
                }
            })
        } else {
            redisService.saveChatMessage(conversationId, message)
        }
    }
}