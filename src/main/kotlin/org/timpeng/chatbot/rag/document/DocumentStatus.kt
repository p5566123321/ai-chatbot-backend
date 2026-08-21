package org.timpeng.chatbot.rag.document

/**
 * Lifecycle of an uploaded document through the ingestion pipeline — same idea as
 * [org.timpeng.chatbot.chat.StreamStatusResponse]'s generating flag for chat streaming, just
 * persisted instead of Redis-cached since ingestion is expected to take much longer than one
 * request. [DocumentService.upload] and [DocumentService.replace] both drive PENDING ->
 * PROCESSING -> READY/FAILED as they run the split -> embed -> vector-store pipeline (see the
 * TODO in [DocumentService.upload]).
 */
enum class DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
}
