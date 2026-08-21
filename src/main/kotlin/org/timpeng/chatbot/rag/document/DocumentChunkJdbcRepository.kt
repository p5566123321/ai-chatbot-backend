package org.timpeng.chatbot.rag.document

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DocumentChunkJdbcRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    fun insert(content: String, documentId: Long, embedding: FloatArray, chunkIndex: Int, pageNumber: Int, tokenCount: Int): UUID {
        val vectorString = embedding.joinToString(prefix = "[", postfix = "]") { it.toString() }
        val id = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO document_chunk (id, content, document_id, embedding, chunk_index, page_number, token_count, created_at)
            VALUES (?, ?, ?, CAST(? AS vector), ?, ?, ?, now())
            """.trimIndent(),
            id, content, documentId, vectorString, chunkIndex, pageNumber, tokenCount
        )
        return id
    }

    // No ON DELETE CASCADE from document_chunk.document_id -> document.id (V4 migration), so a
    // replace/re-ingest has to clear the old chunks itself before inserting the new ones —
    // otherwise stale chunks from the previous content would linger alongside (and interleave
    // chunk_index with) the new ones.
    fun deleteByDocumentId(documentId: Long) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId)
    }

    // Joined to `document` and filtered by its user_id rather than denormalizing an owner column
    // onto document_chunk — this is the only thing standing between one user's retrieval and
    // every other user's chunks, so it's a required param here (see VectorSearchPort's kdoc), not
    // folded into the optional `source` filter below. An INNER JOIN also means a chunk whose
    // document_id somehow ended up null (the FK column has no NOT NULL constraint) fails closed —
    // it simply can't match any owner — rather than leaking into results.
    fun findSimilar(queryVector: FloatArray, ownerId: Long, topK: Int, source: String? = null): List<ChunkRow> {
        val vectorString = queryVector.joinToString(prefix = "[", postfix = "]") { it.toString() }

        val sql = buildString {
            append("SELECT dc.id, dc.content, dc.document_id, dc.embedding <=> CAST(? AS vector) AS distance ")
            append("FROM document_chunk dc ")
            append("JOIN document d ON d.id = dc.document_id ")
            append("WHERE d.user_id = ? ")
            if (source != null) append("AND dc.source = ? ")
            append("ORDER BY distance LIMIT ?")
        }

        val params = if (source != null) {
            arrayOf<Any>(vectorString, ownerId, source, topK)
        } else {
            arrayOf<Any>(vectorString, ownerId, topK)
        }

        return jdbcTemplate.query(sql, params) { rs, _ ->
            ChunkRow(
                id = UUID.fromString(rs.getString("id")),
                content = rs.getString("content"),
                documentId = rs.getLong("document_id"),
                distance = rs.getDouble("distance")
            )
        }
    }
}

data class ChunkRow(
    val id: UUID,
    val content: String,
    val documentId: Long,
    val distance: Double
)