-- V4__add_document_chunk_columns.sql (Flyway migration)
-- Follow-up flagged in V3's comment: document_chunk needs a document_id FK to `document`, plus
-- chunk_index/page_number/token_count to back DocumentChunkJpaEntity and the
-- DocumentChunkJdbcRepository write path DocumentService.upload actually drives. Column names
-- are snake_case to match Hibernate's default naming strategy for DocumentChunkJpaEntity's
-- chunkIndex/pageNumber/tokenCount properties (validated by ddl-auto=validate on startup even
-- though nothing writes through that entity yet).
ALTER TABLE document_chunk
    ADD COLUMN document_id BIGINT REFERENCES document (id),
    ADD COLUMN chunk_index INT NOT NULL DEFAULT 0,
    ADD COLUMN page_number INT,
    ADD COLUMN token_count INT NOT NULL DEFAULT 0;

ALTER TABLE document_chunk
    ALTER COLUMN chunk_index DROP DEFAULT,
    ALTER COLUMN token_count DROP DEFAULT;

CREATE INDEX idx_document_chunk_document_id ON document_chunk (document_id);
