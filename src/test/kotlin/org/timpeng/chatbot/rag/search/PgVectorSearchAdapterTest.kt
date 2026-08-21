package org.timpeng.chatbot.rag.search

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.timpeng.chatbot.rag.document.ChunkRow
import org.timpeng.chatbot.rag.document.DocumentChunkJdbcRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgVectorSearchAdapterTest {
    private val chunkRepository: DocumentChunkJdbcRepository = mockk()
    private lateinit var adapter: VectorSearchPort

    @BeforeEach
    fun setUp() {
        adapter = PgVectorSearchAdapter(chunkRepository)
    }

    // findSimilarChunks

    @Test
    fun `findSimilarChunks maps distance to a similarity score and preserves documentId`() {
        val queryVector = floatArrayOf(0.1f, 0.2f)
        val chunkId = UUID.randomUUID()
        val row = ChunkRow(id = chunkId, content = "hello", documentId = 42L, distance = 0.25)
        every { chunkRepository.findSimilar(queryVector, 1L, 5, null) } returns listOf(row)

        val result = adapter.findSimilarChunks(queryVector, ownerId = 1L, topK = 5)

        assertEquals(1, result.size)
        assertEquals(chunkId.toString(), result[0].id)
        assertEquals("hello", result[0].content)
        assertEquals(42L, result[0].documentId)
        assertEquals(0.75, result[0].score, 1e-9)
    }

    @Test
    fun `findSimilarChunks scopes the search to the given owner`() {
        val queryVector = floatArrayOf(0.1f)
        every { chunkRepository.findSimilar(queryVector, 9L, 3, null) } returns emptyList()

        adapter.findSimilarChunks(queryVector, ownerId = 9L, topK = 3)

        verify { chunkRepository.findSimilar(queryVector, 9L, 3, null) }
    }

    @Test
    fun `findSimilarChunks passes the source filter through to the repository`() {
        val queryVector = floatArrayOf(0.1f)
        every { chunkRepository.findSimilar(queryVector, 1L, 3, "doc-a") } returns emptyList()

        adapter.findSimilarChunks(queryVector, ownerId = 1L, topK = 3, filters = SearchFilters(source = "doc-a"))

        verify { chunkRepository.findSimilar(queryVector, 1L, 3, "doc-a") }
    }

    // upsertChunk

    @Test
    fun `upsertChunk forwards every field to the repository under its own name`() {
        val embedding = floatArrayOf(0.5f, 0.6f)
        val input = DocumentChunkInput(
            content = "chunk text", documentId = 7L, embedding = embedding,
            chunkIndex = 2, pageNumber = 1, tokenCount = 9,
        )
        val id = UUID.randomUUID()
        val contentSlot = slot<String>()
        val documentIdSlot = slot<Long>()
        val embeddingSlot = slot<FloatArray>()
        val chunkIndexSlot = slot<Int>()
        val pageNumberSlot = slot<Int>()
        val tokenCountSlot = slot<Int>()
        every {
            chunkRepository.insert(
                content = capture(contentSlot),
                documentId = capture(documentIdSlot),
                embedding = capture(embeddingSlot),
                chunkIndex = capture(chunkIndexSlot),
                pageNumber = capture(pageNumberSlot),
                tokenCount = capture(tokenCountSlot),
            )
        } returns id

        val result = adapter.upsertChunk(input)

        assertEquals(id.toString(), result)
        assertEquals("chunk text", contentSlot.captured)
        assertEquals(7L, documentIdSlot.captured)
        assertTrue(embedding.contentEquals(embeddingSlot.captured))
        assertEquals(2, chunkIndexSlot.captured)
        assertEquals(1, pageNumberSlot.captured)
        assertEquals(9, tokenCountSlot.captured)
    }
}
