package org.timpeng.chatbot.chat

/**
 * Payload enqueued by [ChatService.streamChat] and consumed by [ChatJobHandler]. Deliberately
 * just the conversationId — not the message history — since [ChatJobHandler] re-derives it via
 * [org.timpeng.chatbot.conversation.ConversationHistoryService] at execution time (a cache hit,
 * because the user message was already saved+cached synchronously before enqueue). That keeps
 * this trivially JSON-serializable and avoids threading JPA entities through a Redis Stream.
 *
 * ownerId rides along too — [LlmProvider.streamGenerate][org.timpeng.chatbot.llm.LlmProvider]
 * needs it (RAG retrieval is scoped per-owner), and `streamChat` already has it in hand, having
 * just validated it via `requireOwnedConversation` right before enqueueing. Re-deriving it
 * instead (e.g. from `Conversation.ownerId`) would mean an extra DB round-trip in the async
 * handler and having to handle that field's nullability for pre-auth conversations (ADR-007) —
 * this plain Long is just as JSON-trivial as conversationId, so it travels the same way.
 */
data class ChatJobPayload(val conversationId: String, val ownerId: Long)
