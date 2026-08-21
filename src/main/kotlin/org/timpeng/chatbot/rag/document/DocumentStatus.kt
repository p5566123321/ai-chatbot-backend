package org.timpeng.chatbot.rag.document

/**
 * Lifecycle of an uploaded document through the (not-yet-built) ingestion pipeline — same idea as
 * [org.timpeng.chatbot.chat.StreamStatusResponse]'s generating flag for chat streaming, just
 * persisted instead of Redis-cached since ingestion is expected to take much longer than one
 * request. [DocumentService] only ever produces [PENDING] today; PROCESSING/READY/FAILED are
 * reserved for the split -> embed -> vector-store pipeline once it's wired up (see the TODO in
 * [DocumentService.upload]).
 */
enum class DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
}
