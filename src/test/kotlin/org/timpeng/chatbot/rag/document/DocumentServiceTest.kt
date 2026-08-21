package org.timpeng.chatbot.rag.document

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import org.timpeng.chatbot.exception.DocumentNotFoundException
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.search.DocumentChunkInput
import org.timpeng.chatbot.rag.search.VectorSearchPort
import org.timpeng.chatbot.rag.splitting.TextSplitter
import java.util.Optional
import kotlin.test.assertEquals

class DocumentServiceTest {
    private val documentRepository: DocumentRepository = mockk()
    private val embeddingProvider: EmbeddingProvider = mockk()
    private val textSplitter: TextSplitter = mockk()
    private val vectorSearchPort: VectorSearchPort = mockk()

    private lateinit var documentService: DocumentService

    private val ownerId = 1L

    @BeforeEach
    fun setUp() {
        documentService = DocumentService(documentRepository, embeddingProvider, textSplitter, vectorSearchPort)
    }

    // upload

    @Test
    fun `upload persists the document with READY status and the caller as owner once processing succeeds`() = runBlocking {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello world".toByteArray())
        val slot = slot<DocumentJpaEntity>()
        every { documentRepository.save(capture(slot)) } answers { slot.captured }
        every { textSplitter.split(any()) } returns emptyList()

        val result = documentService.upload(file, ownerId)

        assertEquals("notes.txt", slot.captured.title)
        assertEquals("hello world", slot.captured.content)
        assertEquals(DocumentStatus.READY, slot.captured.status)
        assertEquals(ownerId, slot.captured.userId)
        assertEquals(slot.captured, result)
    }

    @Test
    fun `upload marks the document FAILED when the pipeline throws`() = runBlocking {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello world".toByteArray())
        val slot = slot<DocumentJpaEntity>()
        every { documentRepository.save(capture(slot)) } answers { slot.captured }
        every { textSplitter.split(any()) } throws RuntimeException("boom")

        val result = documentService.upload(file, ownerId)

        assertEquals(DocumentStatus.FAILED, slot.captured.status)
        assertEquals(slot.captured, result)
    }

    @Test
    fun `upload defaults title to untitled when originalFilename is blank`() = runBlocking {
        val file = MockMultipartFile("file", "", "text/plain", "hi".toByteArray())
        val slot = slot<DocumentJpaEntity>()
        every { documentRepository.save(capture(slot)) } answers { slot.captured }
        every { textSplitter.split(any()) } returns emptyList()

        documentService.upload(file, ownerId)

        assertEquals("untitled", slot.captured.title)
    }

    @Test
    fun `upload throws for an empty file`() {
        val file = MockMultipartFile("file", "empty.txt", "text/plain", ByteArray(0))

        assertThrows<IllegalArgumentException> {
            runBlocking { documentService.upload(file, ownerId) }
        }
    }

    @Test
    fun `upload splits, embeds, and upserts each chunk with its index, and returns the saved document`() = runBlocking {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello world foo bar baz".toByteArray())
        val savedDocument = DocumentJpaEntity(
            id = 42L,
            uuid = "doc-uuid",
            title = "notes.txt",
            content = "hello world foo bar baz",
            status = DocumentStatus.PENDING,
            userId = ownerId,
        )
        every { documentRepository.save(any()) } returns savedDocument
        every { textSplitter.split("hello world foo bar baz") } returns listOf("hello world", "foo bar baz")
        coEvery { embeddingProvider.embed("hello world") } returns floatArrayOf(0.1f)
        coEvery { embeddingProvider.embed("foo bar baz") } returns floatArrayOf(0.2f)
        val inputs = mutableListOf<DocumentChunkInput>()
        every { vectorSearchPort.upsertChunk(capture(inputs)) } returns "chunk-id"

        val result = documentService.upload(file, ownerId)

        assertEquals(savedDocument, result)
        assertEquals(2, inputs.size)

        assertEquals("hello world", inputs[0].content)
        assertEquals(42L, inputs[0].documentId)
        assertEquals(0, inputs[0].chunkIndex)
        assertEquals(2, inputs[0].tokenCount) // "hello world" — 2 words

        assertEquals("foo bar baz", inputs[1].content)
        assertEquals(42L, inputs[1].documentId)
        assertEquals(1, inputs[1].chunkIndex)
        assertEquals(3, inputs[1].tokenCount) // "foo bar baz" — 3 words
    }

    // getDocument

    @Test
    fun `getDocument returns the document when found for the owner`() {
        val document = DocumentJpaEntity(
            id = 1L, uuid = "doc-uuid", title = "t", content = "c",
            status = DocumentStatus.READY, userId = ownerId,
        )
        every { documentRepository.findByUuidAndUserId("doc-uuid", ownerId) } returns Optional.of(document)

        val result = documentService.getDocument("doc-uuid", ownerId)

        assertEquals(document, result)
    }

    @Test
    fun `getDocument throws 404 when not found or not owned by the caller`() {
        every { documentRepository.findByUuidAndUserId("missing", ownerId) } returns Optional.empty()

        assertThrows<DocumentNotFoundException> {
            documentService.getDocument("missing", ownerId)
        }
    }

    // deleteDocument

    @Test
    fun `deleteDocument deletes the document's chunks then the document when found for the owner`() {
        val document = DocumentJpaEntity(
            id = 1L, uuid = "doc-uuid", title = "t", content = "c",
            status = DocumentStatus.READY, userId = ownerId,
        )
        every { documentRepository.findByUuidAndUserId("doc-uuid", ownerId) } returns Optional.of(document)
        every { vectorSearchPort.deleteChunksForDocument(1L) } returns Unit
        every { documentRepository.delete(document) } returns Unit

        documentService.deleteDocument("doc-uuid", ownerId)

        verifyOrder {
            vectorSearchPort.deleteChunksForDocument(1L)
            documentRepository.delete(document)
        }
    }

    @Test
    fun `deleteDocument throws 404 when not found or not owned by the caller, and deletes nothing`() {
        every { documentRepository.findByUuidAndUserId("missing", ownerId) } returns Optional.empty()

        assertThrows<DocumentNotFoundException> {
            documentService.deleteDocument("missing", ownerId)
        }

        verify(exactly = 0) { vectorSearchPort.deleteChunksForDocument(any()) }
        verify(exactly = 0) { documentRepository.delete(any()) }
    }
}
