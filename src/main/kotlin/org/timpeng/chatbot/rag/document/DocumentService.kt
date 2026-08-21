package org.timpeng.chatbot.rag.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.timpeng.chatbot.exception.DocumentNotFoundException
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.search.DocumentChunkInput
import org.timpeng.chatbot.rag.search.VectorSearchPort
import org.timpeng.chatbot.rag.splitting.TextSplitter
import java.util.*

/**
 * Owns document upload + status lookup. Mirrors ChatService's shape at the request boundary:
 * validate and persist synchronously so a bad upload 400s/404s immediately instead of failing
 * deep inside an async pipeline.
 *
 * [upload] runs the split -> embed -> upsert pipeline (TextSplitter / EmbeddingProvider /
 * VectorSearchPort) synchronously and inline. It never advances [DocumentStatus] past `PENDING`
 * — status transitions and moving this behind the job queue (mirroring ChatQueueConfig, once it
 * reliably takes longer than a request) are still TODO; see the TODO in [upload].
 */
@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val embeddingProvider: EmbeddingProvider,
    private val textSplitter: TextSplitter,
    private val vectorSearchPort: VectorSearchPort,
) {

    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    suspend fun upload(file: MultipartFile, ownerId: Long): DocumentJpaEntity {
        require(!file.isEmpty) { "Uploaded file is empty" }
        val title = file.originalFilename?.takeIf { it.isNotBlank() } ?: "untitled"

        // Naive text decode — fine for .txt/.md, garbage for PDF/DOCX/etc. Real per-format
        // parsing belongs to the ingestion pipeline (see class kdoc), not this upload endpoint.
        val content = String(file.bytes, Charsets.UTF_8)

        val document = documentRepository.save(
            DocumentJpaEntity(
                uuid = UUID.randomUUID().toString(),
                title = title,
                content = content,
                status = DocumentStatus.PENDING,
                userId = ownerId,
            )
        )

        logger.info(
            "Document uploaded: uuid={}, ownerId={}, sizeBytes={}",
            document.uuid, ownerId, file.size,
        )

        // TODO(RAG pipeline): split(content) -> embed each chunk -> vectorSearchPort.upsertChunk,
        // then flip status PENDING -> PROCESSING -> READY/FAILED (persist totalChunks too). Move
        // this behind the job queue (mirroring ChatQueueConfig) once it reliably takes longer
        // than a request — DocumentStatus/GET-by-id already exist to support polling the same way
        // ChatController.streamStatus does for chat.
        val contentList = textSplitter.split(document.content)
        for ((index, chunkContent) in contentList.withIndex()) {
            val array = embeddingProvider.embed(chunkContent)
            val input = DocumentChunkInput(
                content = chunkContent,
                documentId = document.id,
                embedding = array,
                chunkIndex = index,
                // Naive text splitting has no page concept yet (see class kdoc) — 0 until a
                // format-aware splitter tracks source pages.
                pageNumber = 0,
                // Rough proxy until a real tokenizer is wired in — word count, not token count.
                tokenCount = chunkContent.trim().split(Regex("\\s+")).size,
            )
            vectorSearchPort.upsertChunk(input)
        }

        return document
    }

    fun getDocument(documentId: String, ownerId: Long): DocumentJpaEntity {
        return documentRepository.findByUuidAndUserId(documentId, ownerId)
            .orElseThrow { DocumentNotFoundException("Document not found: $documentId") }
    }

    fun checkDocument(ownerId: Long): Boolean {
        return documentRepository.existsByUserId(ownerId)
    }

    fun list(userId: Long): List<DocumentResponse> {
        // A user with no documents yet is a normal, valid state — not a 404 (that's reserved for
        // an unresolvable/not-owned single documentId, per getDocument above).
        return documentRepository.findAllByUserId(userId).map(DocumentResponse::from)
    }

    fun deleteDocument(documentId: String, ownerId: Long) {
        // Ownership check + 404, same as getDocument/replace — without this, uuid alone would let
        // any caller delete any other user's document.
        val document = getDocument(documentId, ownerId)

        // Explicit bulk delete rather than relying on DocumentJpaEntity.chunks' cascade/orphanRemoval
        // to clean these up as a side effect of removing the parent — same reasoning as replace's
        // upfront vectorSearchPort.deleteChunksForDocument call: one DELETE by document_id instead
        // of Hibernate lazy-loading every chunk row just to remove it.
        vectorSearchPort.deleteChunksForDocument(document.id)
        documentRepository.delete(document)
    }

    suspend fun replace(file: MultipartFile, documentId: String, ownerId: Long): DocumentJpaEntity {
        require(!file.isEmpty) { "Uploaded file is empty" }

        // Ownership check + 404, same as getDocument — a documentId that doesn't resolve to this
        // caller's document must not be distinguishable from one that doesn't exist at all.
        val document = getDocument(documentId, ownerId)

        val title = file.originalFilename?.takeIf { it.isNotBlank() } ?: "untitled"
        // Same naive text decode as upload — see its comment.
        val content = String(file.bytes, Charsets.UTF_8)

        // Clear the old chunks before re-splitting/re-embedding the new content, otherwise stale
        // chunks from the previous content would linger alongside the new ones.
        vectorSearchPort.deleteChunksForDocument(document.id)

        document.title = title
        document.content = content
        document.status = DocumentStatus.PENDING

        // Same split -> embed -> upsert pipeline as upload (see its TODO: still synchronous/inline,
        // still never advances status past PENDING).
        val contentList = textSplitter.split(document.content)
        for ((index, chunkContent) in contentList.withIndex()) {
            val array = embeddingProvider.embed(chunkContent)
            val input = DocumentChunkInput(
                content = chunkContent,
                documentId = document.id,
                embedding = array,
                chunkIndex = index,
                pageNumber = 0,
                tokenCount = chunkContent.trim().split(Regex("\\s+")).size,
            )
            vectorSearchPort.upsertChunk(input)
        }
        document.totalChunks = contentList.size

        logger.info(
            "Document replaced: uuid={}, ownerId={}, sizeBytes={}",
            document.uuid, ownerId, file.size,
        )

        return documentRepository.save(document)
    }
}
