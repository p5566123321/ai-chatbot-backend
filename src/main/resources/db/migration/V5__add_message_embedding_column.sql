-- V5__add_message_embedding_column.sql (Flyway migration)
-- Backs the chat message embedding feature (MessageEmbeddingService): every message saved
-- through the existing chat endpoints gets embedded async, after commit, when
-- app.message-embedding.enabled is true (default false — see application.yaml). Nullable since
-- rows are inserted first and embedded afterwards (and never, while the flag stays off).
-- vector(768) matches document_chunk's column (V2__enable_pgvector.sql) — both share the same
-- EmbeddingProvider/model/output-dimensionality (app.llm.gemini.embedding-*), so no new provider
-- or dimension config is needed for this table.
ALTER TABLE messages
    ADD COLUMN embedding vector(768);

CREATE INDEX ON messages USING hnsw (embedding vector_cosine_ops);
