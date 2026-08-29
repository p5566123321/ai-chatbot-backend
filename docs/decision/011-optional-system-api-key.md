# ADR-011: System-wide Gemini API key is optional (BYOK-required mode)

**Status:** Accepted

## Context

ADR-010 introduced BYOK (`User.geminiApiKeyCiphertext`) as an *override* on top of a system-wide
`GOOGLE_API_KEY` that `GenAIConfig` always required — the app failed to start without it, and
`GeminiEmbeddingProvider` (RAG) had no BYOK path at all, unconditionally depending on that same
system-wide `Models` bean.

Some deployments don't want to hold a system-wide key at all — every caller should be required to
bring their own, with no fallback that quietly bills/rate-limits against an operator-held key. That
wasn't previously possible: unsetting `GOOGLE_API_KEY` crashed the application at startup
(`Client()`'s no-arg constructor reads the env var itself and throws if neither an API key nor a
Vertex AI project/location is configured), because `GenAIConfig`'s beans were unconditional.

RAG's embedding pipeline has no equivalent to BYOK (see ADR-010's Context: fixed `vector(768)`
columns, `EmbeddingProvider.embed` not carrying an `ownerId`) — a deployment with no system key
therefore cannot support RAG for *anyone*, not just callers without their own key. This is a
deployment-wide capability gate, not a per-user one.

## Decision

`app.llm.gemini.system-api-key` (env `GOOGLE_API_KEY`, still the same env var name) becomes
**optional**. `GenAIConfig`'s `Client`/`Models` beans are built explicitly from the resolved
property instead of relying on `Client()`'s implicit env lookup, and return `null` when the
property is blank — a `null` return from a `@Bean` method registers no bean at all, and Kotlin's
nullable parameter types (`Client?`, `Models?`) tell Spring the resulting dependencies are
optional too, so nothing downstream fails to wire up.

Two independent consumers, two different failure shapes:

- **Chat** (`GeminiClientFactory.modelsFor`): still tries BYOK first, then falls back to the
  system default *if one exists*. A caller with neither now gets `MissingApiKeyException` → 400
  `MISSING_API_KEY` — a clear, per-caller-actionable error ("set your own key"), not a silent
  crash or an opaque 503.
- **RAG** (`GeminiEmbeddingProvider`, `DocumentService`, `MessageEmbeddingService`, `RagService`):
  no BYOK path exists, so a missing system key disables RAG for the whole deployment.
  `GeminiEmbeddingProvider` is no longer `@Service`/component-scanned at all — it's wired up by an
  explicit `@Bean` factory method in `GenAIConfig` (`models?.let { GeminiEmbeddingProvider(it, ...) }`),
  the same null-propagation trick as `Client`/`Models` themselves. (A class-level
  `@ConditionalOnBean(Models::class)` on the `@Service` was tried first and doesn't work reliably —
  Spring Boot's own docs warn it's registration-order dependent on component-scanned classes; it let
  the bean get instantiated anyway and fail to autowire a required `Models`, crashing the container.
  See "Options considered" below.) The three consumers all take `EmbeddingProvider?` now:
  - `RagService.buildPrompt` treats "no provider" the same as "nothing useful to augment with" —
    returns the raw query unchanged, same as its existing no-documents/RAG-disabled fallbacks.
  - `MessageEmbeddingService.embedAsync` no-ops, same spirit as its existing
    pre-auth-conversation/switch-disabled early returns.
  - `DocumentService.upload`/`replace` reject outright with `RagUnavailableException` → 503
    `RAG_UNAVAILABLE`, *before* persisting anything — accepting an upload that can never be
    embedded would leave a silently-broken `FAILED` document instead of an honest "this feature
    isn't available here."

## Options considered

### `@Bean` factory method (chosen) vs. class-level `@ConditionalOnBean(Models::class)`

Tried `@ConditionalOnBean(Models::class)` directly on `GeminiEmbeddingProvider`'s `@Service`
annotation first, alongside its existing `@ConditionalOnProperty`. It compiled and looked correct,
but failed in an actual no-key deployment: Cloud Run's revision crashed on startup with
`UnsatisfiedDependencyException: ... No qualifying bean of type 'com.google.genai.Models' available`
— the condition didn't prevent `GeminiEmbeddingProvider` from being instantiated, it just meant
Spring tried to autowire a `Models` that didn't exist and failed hard instead of skipping the bean.
This matches Spring Boot's own documented restriction: `@ConditionalOnBean` is only reliable on
`@Configuration`/`@Bean` methods, because auto-configuration classes are guaranteed to process in a
known order relative to each other, while a `@Component`-scanned class's conditions can evaluate
before the target bean has been registered at all.

Moved the wiring into `GenAIConfig.geminiEmbeddingProvider`, an explicit `@Bean` factory method
using the same `models?.let { ... }` null-propagation already proven to work for `Client`/`Models`
themselves in the same file. `GeminiEmbeddingProvider` itself dropped `@Service` and both
conditional annotations entirely — it's now a plain class Spring only ever sees as a `@Bean`
method's return value.

### Nullable beans + explicit per-consumer handling (chosen) vs. a no-op `NoopEmbeddingProvider` stub

A stub implementation of `EmbeddingProvider` that throws on `embed()` would have meant fewer call
sites to touch (no nullability to thread through), relying on `DocumentService`'s existing
try/catch-into-`FAILED` handling. Rejected because:

- `RagService.buildPrompt` calls `embed()` with no surrounding try/catch — a throwing stub would
  surface as an unhandled 500 mid-chat-request instead of the existing graceful "fall back to the
  raw query" path every other RAG-off case already takes.
- A document silently landing in `FAILED` status reads as "something went wrong with your file,"
  not "this server doesn't support RAG" — `RagUnavailableException` at the upload boundary is a
  more honest signal.

### `MissingApiKeyException` → 400 vs. reusing `LlmException` → 503

`LlmException` already existed and maps to 503 `LLM_UNAVAILABLE`, which would have meant no new
exception type. Rejected because a caller with no BYOK key isn't looking at a transient external
outage (what 503 implies) — it's a fixable client-side configuration gap. A distinct 400 with a
`MISSING_API_KEY` error code lets the frontend show "add your API key in Settings" instead of
"try again later."

## Consequences

- Deployments that still want a system-wide fallback set `GOOGLE_API_KEY` exactly as before —
  behavior for them is unchanged (ADR-010's BYOK-overrides-default flow still applies).
- Deployments that unset it get a BYOK-required chat experience and RAG disabled outright, with no
  code changes beyond the env var — this ADR doesn't add a separate "RAG enabled" toggle, the
  presence of the system key *is* the toggle.
- `GeminiEmbeddingProvider`, `RagService`, `DocumentService`, `MessageEmbeddingService` all now
  take `EmbeddingProvider?`/depend on an optional `Models` bean — any future consumer of
  `EmbeddingProvider` needs to handle absence the same way, not assume a bean always exists.

## Future considerations

| Item | Description |
|---|---|
| Per-deployment RAG toggle independent of the system key | Right now "no system key" and "no RAG" are the same switch; splitting them would need a real BYOK path for embeddings (ADR-010's blocked follow-up) or a separate embedding-only key. |
| Frontend UX for `MISSING_API_KEY`/`RAG_UNAVAILABLE` | This ADR only covers the backend contract (error codes); surfacing a "add your key" prompt in the UI is a frontend follow-up. |
