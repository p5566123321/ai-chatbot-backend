package org.timpeng.chatbot.rag.document

import java.time.Instant

data class DocumentResponse(
    val id: String,
    val title: String,
    val status: DocumentStatus,
    val uploadedAt: Instant,
    val totalChunks: Int,
) {
    companion object {
        fun from(document: DocumentJpaEntity): DocumentResponse =
            DocumentResponse(
                id = document.uuid,
                title = document.title,
                status = document.status,
                uploadedAt = document.uploadedAt,
                totalChunks = document.totalChunks,
            )
    }
}
