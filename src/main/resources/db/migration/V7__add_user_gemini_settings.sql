-- V7__add_user_gemini_settings.sql (Flyway migration)
-- Per-user Gemini generation config (User.geminiSettings / GeminiSettings, GeminiProvider),
-- exposed via GET /api/users/me and PATCH /api/users/me/gemini-settings (UserController). All
-- columns nullable with no default: null means "no override", i.e. GeminiProvider omits that
-- field from the GenerateContentConfig it builds and the Gemini API falls back to its own
-- default for that parameter, same semantics as the request-side GeminiSettings/
-- UpdateGeminiSettingsRequest fields being nullable.
ALTER TABLE users
    ADD COLUMN system_instruction  TEXT,
    ADD COLUMN temperature         REAL,
    ADD COLUMN top_p               REAL,
    ADD COLUMN top_k               REAL,
    ADD COLUMN candidate_count     INT,
    ADD COLUMN max_output_tokens   INT;
