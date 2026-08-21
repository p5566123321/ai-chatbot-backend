package org.timpeng.chatbot.rag

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.timpeng.chatbot.rag.document.Document
import org.timpeng.chatbot.rag.document.DocumentService
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.search.VectorSearchPort

@Service
class RagService(
    private val embeddingProvider: EmbeddingProvider,
    private val vectorSearchPort: VectorSearchPort,
    private val documentService: DocumentService,
    // How many candidate chunks pgvector returns, and the minimum similarity score (see
    // RetrievedChunk.score — higher is more similar, cosine similarity range is [-1, 1]) a chunk
    // must clear to make it into the prompt. Both are retrieval-quality knobs meant to be tuned
    // without a redeploy (env RAG_SEARCH_TOP_K / RAG_SEARCH_SIMILARITY_THRESHOLD).
    @Value("\${app.rag.search.top-k:5}") private val topK: Int,
    @Value("\${app.rag.search.similarity-threshold:0.0}") private val similarityThreshold: Double,
) {
    private val logger = LoggerFactory.getLogger(RagService::class.java)

    init {
        require(topK > 0) { "topK must be positive, was $topK" }
        require(similarityThreshold in -1.0..1.0) {
            "similarityThreshold must be within [-1.0, 1.0], was $similarityThreshold"
        }
    }

    suspend fun buildPrompt(userQuery: String, ownerId: Long): String {
        if(!documentService.checkDocument(ownerId)){
            logger.info("[RAG] Document not found for $ownerId")
            return userQuery
        }

        val queryVector = embeddingProvider.embed(userQuery)
        val relevantChunks = vectorSearchPort.findSimilarChunks(queryVector, ownerId, topK = topK)
            .filter { it.score >= similarityThreshold }

        if(relevantChunks.isEmpty()){
            logger.info("[RAG] Document Chunks not found for $ownerId")
            return userQuery
        }

        val context = relevantChunks.joinToString("\n\n") { it.content }
        val prompt = """
            Answer the questions based on the following information. If there is no relevant information in the information, please explain clearly：

            $context

            Question：$userQuery
        """.trimIndent()

        logger.info("[RAG] Document found for $ownerId ")

        return prompt
    }

}