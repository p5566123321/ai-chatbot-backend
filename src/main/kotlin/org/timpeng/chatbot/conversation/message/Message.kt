package org.timpeng.chatbot.conversation.message

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.timpeng.chatbot.conversation.Conversation
import java.time.LocalDateTime

// `messages.embedding` (V5__add_message_embedding_column.sql) is deliberately NOT mapped here,
// unlike DocumentChunkJpaEntity.embedding: this entity is saved via messageRepository.save() on
// every single chat turn (ConversationService.saveMessage), and Hibernate has no built-in JDBC
// type for pgvector's `vector` column — binding even a null PGvector through JPA on that hot path
// fails the INSERT outright ("column \"embedding\" is of type vector but expression is of type
// bytea"). ddl-auto=validate doesn't require every DB column to be entity-mapped, so leaving it
// out here is safe: MessageEmbeddingJdbcRepository owns all reads/writes of that column via raw
// JDBC (CAST(? AS vector)), same as DocumentChunkJdbcRepository does for document_chunk.
@Entity
@Table(name = "messages")
data class Message(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    val conversation: Conversation,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val role: Role,

    @Column(columnDefinition = "TEXT", nullable = false)
    val content: String,

    @CreationTimestamp
    val createdAt: LocalDateTime? = null
)
