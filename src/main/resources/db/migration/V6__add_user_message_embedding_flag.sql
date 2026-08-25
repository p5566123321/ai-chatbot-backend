-- V6__add_user_message_embedding_flag.sql (Flyway migration)
-- Per-user on/off switch for the chat message embedding feature (MessageEmbeddingService),
-- controlled from the UI (GET/PATCH /api/users/me...) rather than a static app.* config value —
-- see V5__add_message_embedding_column.sql for the messages.embedding column this feeds.
-- Defaults to false: embedding costs a real Gemini API call per message, so it's opt-in per user.
ALTER TABLE users
    ADD COLUMN message_embedding_enabled BOOLEAN NOT NULL DEFAULT false;
