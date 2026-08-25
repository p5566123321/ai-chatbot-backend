-- Phase 6 remainder: per-user Gemini model choice + BYOK API key (docs/decision/010).
-- Both nullable, same "null = no override / not set" convention as V7's geminiSettings columns.
-- gemini_model is validated against a fixed whitelist (AllowedGeminiModels) at the application
-- layer, not via a DB CHECK constraint, so the allowed set can change without a migration.
-- gemini_api_key_ciphertext holds Base64(iv || AES-256-GCM ciphertext+tag) — never plaintext,
-- see ApiKeyCipher. It is deliberately its own column rather than part of the geminiSettings
-- embeddable, since UserResponse echoes geminiSettings back wholesale and this must never be
-- serialized into an API response.
ALTER TABLE users ADD COLUMN gemini_model VARCHAR(100);
ALTER TABLE users ADD COLUMN gemini_api_key_ciphertext TEXT;
