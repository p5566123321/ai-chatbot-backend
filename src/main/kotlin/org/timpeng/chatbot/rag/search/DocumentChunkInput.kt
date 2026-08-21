package org.timpeng.chatbot.rag.search

data class DocumentChunkInput(
    val content: String,
    val documentId: Long,
    val embedding: FloatArray,
    val chunkIndex: Int,
    val pageNumber: Int,
    val tokenCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentChunkInput

        if (content != other.content) return false
        if (documentId != other.documentId) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}