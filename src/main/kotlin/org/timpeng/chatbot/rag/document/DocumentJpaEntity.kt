package org.timpeng.chatbot.rag.document

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "document", indexes = [Index(name = "idx_document_uuid", columnList = "uuid", unique = true)])
class DocumentJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // Client-facing id (ADR-004's pattern for Conversation): never leak the auto-increment id
    // into a URL.
    @Column(nullable = false, unique = true)
    val uuid: String,

    // var, not val: DocumentService.replace mutates these in place on the existing row (same
    // uuid/id) rather than creating a new document, mirroring how status/totalChunks below are
    // already mutable for the same reason.
    var title: String,

    @Column(columnDefinition = "TEXT")
    var content: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DocumentStatus = DocumentStatus.PENDING,

    val uploadedAt: Instant = Instant.now(),

    var totalChunks: Int = 0,

    // Plain FK, no JPA relation to User — mirrors Conversation.ownerId (ADR-007): userId comes
    // straight off the JWT via @CurrentUserId and is never re-validated against the users table
    // per request, so a managed User relation here would just be an unnecessary extra query.
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @OneToMany(mappedBy = "document", cascade = [CascadeType.ALL], orphanRemoval = true)
    val chunks: MutableList<DocumentChunkJpaEntity> = mutableListOf(),
)
