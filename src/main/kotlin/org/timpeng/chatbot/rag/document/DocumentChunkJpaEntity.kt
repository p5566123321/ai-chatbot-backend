package org.timpeng.chatbot.rag.document

import com.pgvector.PGvector
import jakarta.persistence.*
import java.util.*


@Entity
@Table(name = "document_chunk")
class DocumentChunkJpaEntity(
    @Id
    @GeneratedValue
    val id: UUID = UUID.randomUUID(),

    @Column(columnDefinition = "text")
    val content: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    val document: DocumentJpaEntity,

    @Column(columnDefinition = "vector(768)")
    var embedding: PGvector? = null,

    val chunkIndex: Int,

    val pageNumber: Int?,

    val tokenCount: Int,

)