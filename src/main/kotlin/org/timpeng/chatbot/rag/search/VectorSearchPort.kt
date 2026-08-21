package org.timpeng.chatbot.rag.search

import java.time.Instant

interface VectorSearchPort {
    // ownerId is a required, dedicated parameter rather than an optional SearchFilters field on
    // purpose: every chunk retrieval must be scoped to the caller's own documents (there is no
    // legitimate cross-user search), and an optional filter is exactly the shape that lets that
    // scoping get forgotten at a call site — which is how this went unscoped in the first place.
    fun findSimilarChunks(
        queryVector: FloatArray,
        ownerId: Long,
        topK: Int,
        filters: SearchFilters = SearchFilters.empty()
    ): List<RetrievedChunk>

    fun upsertChunk(chunk: DocumentChunkInput): String  // 回傳 chunk id

    // Used by DocumentService.replace to clear a document's previous chunks before re-splitting
    // and re-embedding the new content.
    fun deleteChunksForDocument(documentId: Long)
}

data class SearchFilters(
    val source: String? = null,
    val minCreatedAt: Instant? = null
) {
    companion object {
        fun empty() = SearchFilters()
    }
}
