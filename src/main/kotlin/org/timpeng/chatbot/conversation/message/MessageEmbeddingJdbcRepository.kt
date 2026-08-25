package org.timpeng.chatbot.conversation.message

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Raw JDBC write path for `messages.embedding` (V5__add_message_embedding_column.sql), same
 * split as [org.timpeng.chatbot.rag.document.DocumentChunkJdbcRepository]: the `vector` column
 * needs a `CAST(? AS vector)`, which JPA/Hibernate has no first-class support for, so the write
 * bypasses [MessageRepository] entirely — [Message.embedding] exists only so
 * `ddl-auto=validate` accepts the column.
 */
@Repository
class MessageEmbeddingJdbcRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    fun updateEmbedding(messageId: Long, embedding: FloatArray) {
        val vectorString = embedding.joinToString(prefix = "[", postfix = "]") { it.toString() }
        jdbcTemplate.update(
            "UPDATE messages SET embedding = CAST(? AS vector) WHERE id = ?",
            vectorString, messageId
        )
    }
}
