# ADR-004: Switch Conversation ID Generation to Backend (executes ADR-002's migration path)

**Status:** Accepted

## Context

[ADR-002](002-conversation-id.md) chose frontend-generated UUIDs for the MVP, explicitly deferring backend-generated IDs (Option B) until "authentication is added in the future."

Two things changed since ADR-002 was written:

1. **The frontend never actually implemented Option A.** `app/api/chat/route.ts` and `app/api/chat/stream/route.ts` hardcoded `conversationId ?? "test"` — no `crypto.randomUUID()` call existed anywhere in the client. Every visitor was silently sharing a single `"test"` conversation, defeating the entire point of ADR-002's design. This was found during a review of the streaming architecture, not by design.
2. **Multi-account support moved from a hypothetical to a near-term roadmap item.** The product is currently a demo, but multi-user accounts are the explicitly planned next stage — not a distant "if" anymore.

Given (2), ADR-002's own stated trigger for migrating to Option B — "if authentication is added in the future" — is close enough that doing the migration now, while fixing the bug in (1) anyway, avoids a second round-trip of API changes later.

## Decision

**Adopt Option B from ADR-002 now.** The backend generates and owns conversation IDs.

```
POST /api/conversations                          → 201, { uuid, createdAt }
POST /api/conversations/{id}/messages             → send a message (was POST /api/chat)
POST /api/conversations/{id}/messages/stream       → send a message, streamed (was POST /api/chat/stream)
GET  /api/conversations/{id}/messages             → read history (newly exposed)
```

- `ConversationService.createConversation()` generates the UUID server-side and persists the `Conversation` row.
- The frontend calls `POST /api/conversations` once per browser (no existing ID in `localStorage`), stores the returned `uuid`, and sends it as a path segment on every subsequent request — never in the body, never client-generated.
- A `conversationId` that doesn't resolve to an existing `Conversation` row now returns `404` via `ConversationNotFoundException` (`ConversationHistoryService.getHistory` previously auto-created a row on miss, which masked exactly this kind of bug — a typo'd or stale client-side ID silently started a fresh, indistinguishable conversation instead of surfacing an error).

This also matches the general REST shape ADR-002's Option B sketch used (`POST /api/conversations` → `POST /api/chat/{id}/messages`), just with `/api/chat` folded under `/api/conversations/{id}` instead of kept as a sibling path.

## Consequences

- ✅ Fixes the shared-`"test"`-conversation bug: every browser now gets its own conversation on first load.
- ✅ No schema migration needed — `Conversation.uuid` already existed as a server-assignable column, exactly as ADR-002 anticipated.
- ✅ Sets up the seam ADR-002 called out: when auth lands, `POST /api/conversations` is the one place that needs to require a session and stamp an `ownerId`; no other endpoint shape changes.
- ❌ One extra round-trip before the first message can be sent (mitigated by doing it once on page load and caching the ID in `localStorage`, not per-message).
- ❌ `ChatRequest` no longer carries `conversationId` — any external caller relying on the old flat `POST /api/chat` body shape breaks. There were no external consumers at time of writing (single first-party frontend, demo stage).
