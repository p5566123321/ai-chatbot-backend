# ADR-010: Gemini Model Choice + BYOK (Phase 6 remainder)

**Status:** Accepted

## Context

`docs/roadmap.md` Phase 6 originally scoped three items: model choice, bring-your-own API key
(BYOK), and history window size. The first slice already shipped ahead of this ADR — per-user
`GenerateContentConfig` overrides (temperature/topP/topK/candidateCount/maxOutputTokens/
systemInstruction, `V7__add_user_gemini_settings.sql`, `GeminiProvider.buildGenerationConfig`) —
but that ADR-less rollout is exactly what Phase 6's own note warned against for the remaining two
items: "BYOK key storage (at-rest encryption, key management, keeping keys out of logs/metrics) is
its own security surface worth an ADR rather than a bolt-on." This ADR covers that surface, plus
the smaller model-choice decision that rides along with it. History window size is not part of
this ADR — it has no dependency on the other two and can be pulled forward independently.

Two structural facts shaped the scope:

- `document_chunk.embedding` and `messages.embedding` are fixed `vector(768)` Postgres columns
  (`V2__enable_pgvector.sql`, `V5__add_message_embedding_column.sql`), shared across every user.
  Letting a user pick their own *embedding* model would let them pick a different output
  dimensionality, which breaks inserts/search for that column outright.
- `EmbeddingProvider.embed(text: String): FloatArray` carries no `ownerId` at all today — wiring
  BYOK through it would mean changing that interface and touching `RagService` plus the (not yet
  built) document ingestion pipeline, well outside "add user-configurable Gemini parameters."

Both are pushed out as follow-up work rather than solved here.

## Decision

**Chat-only, for both model choice and BYOK** — `GeminiEmbeddingProvider` is untouched.

- **Model choice**: `GeminiSettings.model: String?`, validated against a fixed whitelist
  (`AllowedGeminiModels.IDS`, a plain Kotlin `Set<String>`) in `UserService.updateGeminiSettings`.
  `null` falls back to the existing `app.llm.gemini.model` default. `GET /api/users/me/gemini-models`
  exposes the same whitelist so the frontend renders a dropdown instead of a free-text field.
- **BYOK**: `User.geminiApiKeyCiphertext: String?`, set via `PATCH /api/users/me/gemini-api-key`
  (full replace — null/blank clears it). Encrypted at rest with AES-256-GCM
  (`org.timpeng.chatbot.crypto.ApiKeyCipher`): a fresh random 12-byte IV per encryption, stored as
  `Base64(iv || ciphertext+tag)` in one column. The AES key comes from `app.byok.encryption-key`
  (env `BYOK_ENCRYPTION_KEY`, `openssl rand -base64 32`) and is validated to be exactly 32 bytes at
  construction — a bad length fails application startup, mirroring how `JwtService` already treats
  `app.jwt.secret` (`Keys.hmacShaKeyFor`'s `WeakKeyException`). No default value is provided
  anywhere, same as `JWT_SECRET`.
- The stored key is decrypted only transiently, inside `GeminiClientFactory.modelsFor(user)`, to
  build a per-call `com.google.genai.Client` (`Client.builder().apiKey(key).build().models`) —
  never cached, never logged. `UserResponse` only ever exposes `hasGeminiApiKey: Boolean`; the
  ciphertext is deliberately kept off `GeminiSettings` (which `UserResponse` echoes back wholesale)
  so there is no accidental path to leaking it through that response.
- `PATCH /api/users/me/gemini-api-key` does **not** call Gemini to validate the key before storing
  it. An invalid key surfaces on the caller's next chat turn through the existing
  `LlmException` → 503 `LLM_UNAVAILABLE` path — no new error handling needed.

## Options considered

### Model choice: fixed whitelist (chosen) vs. free-text passthrough

| | |
|---|---|
| ✅ | Rejects a typo'd/nonexistent model id at save time (400) instead of at the next chat call (503) |
| ✅ | The same whitelist backs a real dropdown in the settings UI — no guessing what to type |
| ❌ | Needs a code change (`AllowedGeminiModels.IDS`) whenever the account's available models change, instead of being maintenance-free |

Free-text was rejected mainly for the UX gap: a mistyped model id would otherwise sit unnoticed
until the user's next chat message fails.

### BYOK encryption: AES-256-GCM + env-var key (chosen) vs. external KMS/Secrets Manager

| | |
|---|---|
| ✅ | No new infrastructure dependency — every other secret in this project (`JWT_SECRET`, `GOOGLE_API_KEY`, `DB_PASSWORD`) is already a plain env var; this follows the same operational model |
| ✅ | JDK-builtin `javax.crypto`, no new library |
| ❌ | No key rotation story beyond "re-encrypt every stored key," and the key's blast radius is "everyone's BYOK key" if `BYOK_ENCRYPTION_KEY` itself leaks |

A KMS integration would improve on both of those `❌` rows, but this project has no existing cloud
KMS/Vault wiring anywhere to build on, and introducing one for a single column is disproportionate
to the current deployment's operational maturity. Revisit if/when other secrets in this project
also outgrow plain env vars.

### BYOK validation timing: store without checking (chosen) vs. validate against Gemini on save

Store-without-checking was preferred to avoid adding a second failure mode (validation call fails
independently of the save itself) and a second external round-trip to a `PATCH` endpoint, at the
cost of the user only discovering a bad key on their next chat message rather than immediately.

## Consequences

- `GeminiProvider.generate`/`streamGenerate` now fetch the caller's `User` row once per call and
  reuse it for `buildGenerationConfig`, the effective model, and `GeminiClientFactory.modelsFor` —
  previously `buildGenerationConfig` re-queried `userRepository` on its own.
- Existing deployments must add `BYOK_ENCRYPTION_KEY` to their environment before upgrading, or the
  application fails to start — intentional, matching `JWT_SECRET`'s existing precedent.
- `GeminiSettings.isEmpty()` now also considers `model`; a new `isGenerationConfigEmpty()` (which
  excludes `model`) is what `buildGenerationConfig` actually checks, so a caller who only overrode
  `model` still gets a `null` `GenerateContentConfig` rather than a builder with nothing set.

## Future considerations

| Item | Description |
|---|---|
| Embedding model choice / BYOK for embeddings | Blocked on the `vector(768)` column being fixed-dimension and `EmbeddingProvider.embed` not carrying an `ownerId` — see Context. |
| History window size | The remaining, independent Phase 6 item — moving `app.conversation.cache.max-msg` from global config to a per-user/per-conversation value. |
| Key rotation | If `BYOK_ENCRYPTION_KEY` ever needs to rotate, every stored `geminiApiKeyCiphertext` needs re-encryption — no tooling for that exists yet. |
