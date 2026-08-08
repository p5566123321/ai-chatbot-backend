package org.timpeng.chatbot.chat

/**
 * Payload enqueued by [ChatService.streamChat] and consumed by [ChatJobHandler]. Deliberately
 * just the conversationId — not the message history — since [ChatJobHandler] re-derives it via
 * [org.timpeng.chatbot.conversation.ConversationHistoryService] at execution time (a cache hit,
 * because the user message was already saved+cached synchronously before enqueue). That keeps
 * this trivially JSON-serializable and avoids threading JPA entities through a Redis Stream.
 */
data class ChatJobPayload(val conversationId: String)
