package org.timpeng.chatbot.rag.document

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface DocumentRepository : JpaRepository<DocumentJpaEntity, Long> {
    // Filtering by userId in the query itself (rather than fetch-then-compare) means a document
    // that exists but belongs to someone else simply doesn't match — same "don't let the response
    // distinguish those two cases" property ConversationService.requireOwnedConversation enforces
    // by hand for conversations.
    fun findByUuidAndUserId(uuid: String, userId: Long): Optional<DocumentJpaEntity>

    // A user can have any number of documents (no uniqueness constraint on user_id) — checkDocument
    // only needs to know whether at least one exists, so this derived query returns a single
    // boolean rather than a singular Optional<DocumentJpaEntity> that Spring Data would throw on
    // (IncorrectResultSizeDataAccessException) the moment a second document is uploaded.
    fun existsByUserId(userId: Long): Boolean

    // Unlike findByUuidAndUserId/existsByUserId above, this is a "findAllBy" query — Spring Data
    // derives a Collection return type for it, not Optional<single>. Wrapping it in Optional would
    // make it throw IncorrectResultSizeDataAccessException the moment a user has more than one
    // document, and a user with zero documents is just an empty list, not a 404.
    fun findAllByUserId(userId: Long): List<DocumentJpaEntity>
}
