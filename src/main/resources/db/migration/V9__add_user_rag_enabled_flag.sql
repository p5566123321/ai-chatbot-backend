-- Per-user switch to opt out of RAG augmentation on chat queries (RagService.buildPrompt).
-- Defaults true so existing behavior (RAG already runs automatically whenever the caller has at
-- least one document, see DocumentService.checkDocument) is unchanged for every current user —
-- this only lets someone turn OFF a thing that already happens, unlike messageEmbeddingEnabled
-- (V6), which opted people INTO a new cost-incurring feature and so defaulted false.
ALTER TABLE users ADD COLUMN rag_enabled BOOLEAN NOT NULL DEFAULT true;
