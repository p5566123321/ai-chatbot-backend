-- V2__enable_pgvector.sql (Flyway migration)
CREATE
EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunk
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT NOT NULL,
    source     VARCHAR(255),
    embedding  vector(768), -- 維度要跟你的 embedding model 對齊
    created_at TIMESTAMP        DEFAULT now()
);

-- 建立向量索引（IVFFlat 或 HNSW，HNSW 查詢品質通常較好）
CREATE INDEX ON document_chunk USING hnsw (embedding vector_cosine_ops);