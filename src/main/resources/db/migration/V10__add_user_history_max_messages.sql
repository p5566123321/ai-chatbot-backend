-- Per-user override for how many history messages get loaded as LLM context / returned by the
-- history endpoint (docs/roadmap.md Phase 6's last remaining item). Nullable — null means "use the
-- global app.conversation.cache.max-msg default", same convention as GeminiSettings' fields.
ALTER TABLE users ADD COLUMN history_max_messages INTEGER;
