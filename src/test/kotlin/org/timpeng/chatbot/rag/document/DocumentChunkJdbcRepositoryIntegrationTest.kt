package org.timpeng.chatbot.rag.document

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises DocumentChunkJdbcRepository's hand-written SQL against a real local Postgres+pgvector
 * — the column list / placeholder count and the actual document_chunk schema (V2 + V4 migrations)
 * aren't meaningfully testable with a mocked JdbcTemplate (that would just assert mocks return
 * what we told them to; see PgVectorSearchAdapterTest for that level). Needs `db` up
 * (`docker compose up -d db`) and DB_URL/DB_USER/DB_PASSWORD in the environment (`source
 * scripts/load-env.sh` first) — same precondition as RedisStreamConsumerIntegrationTest's Redis
 * counterpart.
 */
class DocumentChunkJdbcRepositoryIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repository: DocumentChunkJdbcRepository
    private var userId: Long = 0
    private var documentId: Long = 0

    @BeforeEach
    fun setUp() {
        val url = System.getenv("DB_URL")
        assumeTrue(url != null, "DB_URL not set — run 'source scripts/load-env.sh' first")

        val dataSource = DriverManagerDataSource(url, System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
        jdbcTemplate = JdbcTemplate(dataSource)
        repository = DocumentChunkJdbcRepository(jdbcTemplate)

        // Fresh user + document per test so FK inserts succeed and tests never collide.
        val email = "chunk-repo-test-${UUID.randomUUID()}@example.com"
        userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, email, "hash", LocalDateTime.now(),
        )!!
        documentId = jdbcTemplate.queryForObject(
            """
            INSERT INTO document (uuid, title, content, status, uploaded_at, user_id)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, UUID.randomUUID().toString(), "test doc", "content", "PENDING", LocalDateTime.now(), userId,
        )!!
    }

    @AfterEach
    fun tearDown() {
        if (!::jdbcTemplate.isInitialized) return
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId)
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", documentId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)
    }

    @Test
    fun `insert stores every column under its own name, and findSimilar can retrieve it`() {
        val embedding = FloatArray(768) { 0.01f }

        val id = repository.insert(
            content = "hello world chunk", documentId = documentId, embedding = embedding,
            chunkIndex = 5, pageNumber = 9, tokenCount = 17,
        )

        // Column-level check — this is what would have caught the chunkIndex/pageNumber mix-up
        // and the tokeCount typo directly, without depending on findSimilar's own SELECT.
        val row = jdbcTemplate.queryForMap(
            "SELECT document_id, chunk_index, page_number, token_count FROM document_chunk WHERE id = ?",
            id,
        )
        assertEquals(documentId, row["document_id"])
        assertEquals(5, row["chunk_index"])
        assertEquals(9, row["page_number"])
        assertEquals(17, row["token_count"])

        val results = repository.findSimilar(embedding, ownerId = userId, topK = 5)
        val match = results.find { it.id == id }
        assertTrue(match != null, "inserted chunk was not returned by findSimilar")
        assertEquals("hello world chunk", match!!.content)
        assertEquals(documentId, match.documentId)
        assertEquals(0.0, match.distance, 1e-6) // identical vector compared to itself
    }

    @Test
    fun `findSimilar orders by cosine distance and respects topK`() {
        val near = FloatArray(768) { 0.01f }
        val far = FloatArray(768) { if (it == 0) 1f else 0f }
        repository.insert("near chunk", documentId, near, chunkIndex = 0, pageNumber = 0, tokenCount = 2)
        repository.insert("far chunk", documentId, far, chunkIndex = 1, pageNumber = 0, tokenCount = 2)

        val results = repository.findSimilar(near, ownerId = userId, topK = 1)

        assertEquals(1, results.size)
        assertEquals("near chunk", results[0].content)
    }

    @Test
    fun `findSimilar never returns another user's chunks`() {
        // A second user + document, entirely separate from the userId/documentId set up above.
        val otherEmail = "chunk-repo-test-${UUID.randomUUID()}@example.com"
        val otherUserId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, otherEmail, "hash", LocalDateTime.now(),
        )!!
        val otherDocumentId = jdbcTemplate.queryForObject(
            """
            INSERT INTO document (uuid, title, content, status, uploaded_at, user_id)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, UUID.randomUUID().toString(), "other doc", "content", "PENDING", LocalDateTime.now(), otherUserId,
        )!!

        try {
            val embedding = FloatArray(768) { 0.01f }
            repository.insert("someone else's chunk", otherDocumentId, embedding, chunkIndex = 0, pageNumber = 0, tokenCount = 3)

            // Regression test: findSimilar used to have no owner scoping at all, so this chunk
            // would come back for any caller regardless of which user's document it belongs to.
            val results = repository.findSimilar(embedding, ownerId = userId, topK = 5)

            assertTrue(results.none { it.content == "someone else's chunk" }, "leaked another user's chunk")
        } finally {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", otherDocumentId)
            jdbcTemplate.update("DELETE FROM document WHERE id = ?", otherDocumentId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", otherUserId)
        }
    }
}
