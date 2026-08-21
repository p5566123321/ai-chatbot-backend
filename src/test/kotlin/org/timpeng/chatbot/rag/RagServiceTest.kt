package org.timpeng.chatbot.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.timpeng.chatbot.rag.document.DocumentService
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import org.timpeng.chatbot.rag.search.RetrievedChunk
import org.timpeng.chatbot.rag.search.VectorSearchPort
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagServiceTest {
    private val embeddingProvider: EmbeddingProvider = mockk()
    private val vectorSearchPort: VectorSearchPort = mockk()
    private val documentService: DocumentService = mockk()

    private lateinit var ragService: RagService

    private val ownerId = 1L

    @BeforeEach
    fun setUp() {
        ragService = RagService(embeddingProvider, vectorSearchPort, documentService, topK = 5, similarityThreshold = 0.0)
    }

    @Test
    fun `buildPrompt embeds the query and includes retrieved chunk content in the prompt`() = runBlocking {
        val queryVector = floatArrayOf(0.1f, 0.2f)
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed("What is RAG?") } returns queryVector
        every { vectorSearchPort.findSimilarChunks(queryVector, ownerId, topK = 5) } returns listOf(
            RetrievedChunk(id = "1", content = "RAG stands for Retrieval-Augmented Generation.", documentId = 1L, score = 0.9),
            RetrievedChunk(id = "2", content = "It combines retrieval with generation.", documentId = 1L, score = 0.8),
        )

        val prompt = ragService.buildPrompt("What is RAG?", ownerId)

        assertTrue(prompt.contains("RAG stands for Retrieval-Augmented Generation."))
        assertTrue(prompt.contains("It combines retrieval with generation."))
        assertTrue(prompt.contains("What is RAG?"))
    }

    @Test
    fun `buildPrompt still returns a usable prompt when no chunks are found`() = runBlocking {
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed("unknown topic") } returns floatArrayOf(0.1f)
        every { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 5) } returns emptyList()

        val prompt = ragService.buildPrompt("unknown topic", ownerId)

        assertTrue(prompt.contains("unknown topic"))
    }

    @Test
    fun `buildPrompt scopes retrieval to the caller's own documents`() = runBlocking {
        val otherOwnerId = 2L
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed(any()) } returns floatArrayOf(0.1f)
        every { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 5) } returns emptyList()

        ragService.buildPrompt("question", ownerId)

        // Regression test: findSimilarChunks used to be called with no owner scoping at all,
        // letting one user's retrieval surface another user's document chunks.
        coVerify(exactly = 0) { vectorSearchPort.findSimilarChunks(any(), otherOwnerId, any()) }
        coVerify { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 5) }
    }

    @Test
    fun `buildPrompt skips retrieval entirely and returns the raw query when the caller has no documents`() = runBlocking {
        every { documentService.checkDocument(ownerId) } returns false

        val prompt = ragService.buildPrompt("What is RAG?", ownerId)

        assertEquals("What is RAG?", prompt)
        coVerify(exactly = 0) { embeddingProvider.embed(any()) }
        coVerify(exactly = 0) { vectorSearchPort.findSimilarChunks(any(), any(), any()) }
    }

    @Test
    fun `buildPrompt forwards the configured topK to the vector search`() = runBlocking {
        ragService = RagService(embeddingProvider, vectorSearchPort, documentService, topK = 12, similarityThreshold = 0.0)
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed(any()) } returns floatArrayOf(0.1f)
        every { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 12) } returns emptyList()

        ragService.buildPrompt("question", ownerId)

        coVerify { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 12) }
    }

    @Test
    fun `buildPrompt drops chunks below the configured similarity threshold`() = runBlocking {
        ragService = RagService(embeddingProvider, vectorSearchPort, documentService, topK = 5, similarityThreshold = 0.85)
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed(any()) } returns floatArrayOf(0.1f)
        every { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 5) } returns listOf(
            RetrievedChunk(id = "1", content = "well above threshold", documentId = 1L, score = 0.9),
            RetrievedChunk(id = "2", content = "just below threshold", documentId = 1L, score = 0.8),
        )

        val prompt = ragService.buildPrompt("question", ownerId)

        assertTrue(prompt.contains("well above threshold"))
        assertTrue(!prompt.contains("just below threshold"))
    }

    @Test
    fun `buildPrompt returns the raw query when every candidate falls below the similarity threshold`() = runBlocking {
        ragService = RagService(embeddingProvider, vectorSearchPort, documentService, topK = 5, similarityThreshold = 0.95)
        every { documentService.checkDocument(ownerId) } returns true
        coEvery { embeddingProvider.embed(any()) } returns floatArrayOf(0.1f)
        every { vectorSearchPort.findSimilarChunks(any(), ownerId, topK = 5) } returns listOf(
            RetrievedChunk(id = "1", content = "not similar enough", documentId = 1L, score = 0.5),
        )

        val prompt = ragService.buildPrompt("question", ownerId)

        assertEquals("question", prompt)
    }

    @Test
    fun `constructor rejects a non-positive topK`() {
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            RagService(embeddingProvider, vectorSearchPort, documentService, topK = 0, similarityThreshold = 0.0)
        }
        assertTrue(exception.message!!.contains("topK"))
    }

    @Test
    fun `constructor rejects a similarityThreshold outside of -1 point 0 and 1 point 0`() {
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            RagService(embeddingProvider, vectorSearchPort, documentService, topK = 5, similarityThreshold = 1.5)
        }
        assertTrue(exception.message!!.contains("similarityThreshold"))
    }
}
