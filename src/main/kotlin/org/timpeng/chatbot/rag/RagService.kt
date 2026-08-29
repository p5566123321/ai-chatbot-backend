package org.timpeng.chatbot.rag

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.rag.document.Document
import org.timpeng.chatbot.rag.document.DocumentService
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.search.VectorSearchPort

@Service
class RagService(
    // Null when no system-wide Gemini key is configured (GenAIConfig) — RAG has no BYOK path, so
    // this is a deployment-wide off-switch rather than something a caller can fix per-user.
    private val embeddingProvider: EmbeddingProvider?,
    private val vectorSearchPort: VectorSearchPort,
    private val documentService: DocumentService,
    private val userRepository: UserRepository,
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
        // RAG disabled deployment-wide (no system-wide Gemini key, see GeminiEmbeddingProvider's
        // @ConditionalOnBean) — checked before the per-user opt-out below since it's a cheaper,
        // more fundamental gate: no embedding capability exists at all, not just off for this user.
        if (embeddingProvider == null) {
            return userQuery
        }

        // Per-user opt-out (users.rag_enabled, V9__add_user_rag_enabled_flag.sql), toggled via
        // PATCH /api/users/me/rag-enabled — checked first so a caller who turned RAG off never
        // pays for the checkDocument/embed/vector-search calls below. Defaults true (same as the
        // column default) for a caller with no row at all, matching this method's pre-existing
        // behavior of falling back to the raw query whenever there's nothing useful to augment with.
        val ragEnabled = userRepository.findById(ownerId).map { it.ragEnabled }.orElse(true)
        if (!ragEnabled) {
            logger.info("[RAG] disabled by owner=$ownerId")
            return userQuery
        }

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