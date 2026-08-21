package org.timpeng.chatbot.rag.document

import java.time.Instant
import java.util.UUID

data class Document(
    val id: UUID,
    val filename: String,
    val status: DocumentStatus,
    val uploadedAt: Instant
)

data class InputDocument(
    val text: String
)