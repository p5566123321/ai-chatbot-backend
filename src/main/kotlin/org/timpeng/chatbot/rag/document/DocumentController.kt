package org.timpeng.chatbot.rag.document

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.timpeng.chatbot.auth.CurrentUserId
import java.net.URI

// Deliberately NOT under /api/conversations/{conversationId} like ChatController — a document
// belongs to the uploading user (DocumentJpaEntity.userId), not to any one conversation, so a
// conversationId in the path would be meaningless here. See RagService for how documents feed
// into an answer.
@RestController
@RequestMapping("/api/documents")
class DocumentController(private val documentService: DocumentService) {

    private val logger = LoggerFactory.getLogger(DocumentController::class.java)

    @GetMapping
    fun list(
        @CurrentUserId ownerId: Long,
    ): List<DocumentResponse> {
        logger.info("[REQ] Retrieving documents for $ownerId")
        val docs = documentService.list(ownerId)
        logger.info("[RSP] Found ${docs.size} documents for $ownerId")
        return docs
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun upload(
        @RequestParam("file") file: MultipartFile,
        @CurrentUserId ownerId: Long,
    ): ResponseEntity<DocumentResponse> {
        logger.info("[REQ] document upload, filename={}, sizeBytes={}", file.originalFilename, file.size)

        val document = documentService.upload(file, ownerId)
        return ResponseEntity
            .created(URI.create("/api/documents/${document.uuid}"))
            .body(DocumentResponse.from(document))
    }

    @GetMapping("/{documentId}")
    fun get(
        @PathVariable documentId: String,
        @CurrentUserId ownerId: Long,
    ): DocumentResponse {
        logger.info("[REQ] Retrieving document for $ownerId identifier for $documentId")

        return DocumentResponse.from(documentService.getDocument(documentId, ownerId))
    }

    @DeleteMapping("/{documentId}")
    fun delete(
        @PathVariable documentId: String,
        @CurrentUserId ownerId: Long,
    ): ResponseEntity<Void> {
        documentService.deleteDocument(documentId, ownerId)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{documentId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replace(
        @RequestParam("file") file: MultipartFile,
        @PathVariable documentId: String,
        @CurrentUserId ownerId: Long,
    ): ResponseEntity<DocumentResponse> {
        logger.info("[REQ] document replace, filename={}, sizeBytes={}", file.originalFilename, file.size)

        val document = documentService.replace(file, documentId, ownerId)
        return ResponseEntity
            .created(URI.create("/api/documents/${document.uuid}"))
            .body(DocumentResponse.from(document))
    }
}
