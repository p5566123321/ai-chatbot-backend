package org.timpeng.chatbot.rag.search

import org.springframework.stereotype.Repository
import org.timpeng.chatbot.rag.document.DocumentChunkJdbcRepository

@Repository
class PgVectorSearchAdapter(
    private val chunkRepository: DocumentChunkJdbcRepository
) : VectorSearchPort {

    override fun findSimilarChunks(
        queryVector: FloatArray,
        ownerId: Long,
        topK: Int,
        filters: SearchFilters
    ): List<RetrievedChunk> {
        val rows = chunkRepository.findSimilar(
            queryVector,
            ownerId,
            topK,
            filters.source
        )

        return rows.map {
            RetrievedChunk(
                id = it.id.toString(),
                content = it.content,
                documentId = it.documentId,
                score = 1.0 - it.distance  // distance 轉成 similarity，統一語意
            )
        }
    }

    override fun upsertChunk(chunk: DocumentChunkInput): String {
        val id = chunkRepository.insert(
            content = chunk.content,
            documentId = chunk.documentId,
            embedding = chunk.embedding,
            chunkIndex = chunk.chunkIndex,
            pageNumber = chunk.pageNumber,
            tokenCount = chunk.tokenCount
        )
        return id.toString()
    }

    override fun deleteChunksForDocument(documentId: Long) {
        chunkRepository.deleteByDocumentId(documentId)
    }

    fun FloatArray.toPgVectorString(): String =
        this.joinToString(prefix = "[", postfix = "]") { it.toString() }
}