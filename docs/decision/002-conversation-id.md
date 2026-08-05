# ADR-002: Conversation ID Generation Strategy

**Status:** Superseded by [ADR-004](004-conversation-id-backend-generated.md)

## Context

Every chat session needs a stable identifier so the client can associate multiple messages with the same conversation. The question is: who generates that UUID — the frontend or the backend?

## Decision

**The frontend generates the UUID** and sends it in each request body.

```javascript
// client-side, once per session
const conversationId = crypto.randomUUID();
```

```http
POST /chat
{ "conversationId": "f47ac10b-...", "message": "Hello" }
```

The backend looks up the conversation by this UUID on every request. No separate "create conversation" call is needed.

## Options considered

### Option A — Frontend generates UUID

| | |
|---|---|
| ✅ | No extra round-trip to start a conversation |
| ✅ | ID available before the first message is sent |
| ✅ | Simpler API surface — single `POST /chat` endpoint |
| ✅ | Can be stored in the URL (`/chat/uuid`) immediately |
| ❌ | Client controls the ID; any string can be sent (spoofing risk) |
| ❌ | Harder to enforce ownership if auth is added later |

### Option B — Backend generates UUID

```kotlin
// requires: POST /api/conversations → { conversationId }
// then:     POST /api/chat/{id}/messages
val id = UUID.randomUUID()
conversationRepository.save(Conversation(uuid = id.toString()))
```

| | |
|---|---|
| ✅ | Server fully controls ID creation |
| ✅ | Trivial to tie conversation to an authenticated user at creation time |
| ✅ | No spoofing or collision risk from the client |
| ❌ | Requires an extra API call before the first message |
| ❌ | More complex client and server flow |

## Rationale

This project is a **public, anonymous chatbot** with no authentication in the MVP. Option A fits because:

- The spoofing risk is acceptable — there is no user account to protect.
- Keeping the API to a single endpoint (`POST /chat`) reduces frontend complexity.
- The backend stores both an internal auto-increment `id` and the client `uuid` in the `Conversation` entity, so switching to Option B later only requires generating the UUID server-side and returning it — no schema migration needed.

## Migration path to Option B

If authentication is added in the future:

1. Add `POST /api/conversations` that creates a `Conversation` and returns its `uuid`.
2. Validate on `POST /chat` that `conversationId` matches a conversation belonging to the authenticated user.
3. Remove client-side UUID generation.

No database schema change is required — the `uuid` column and `id` PK are already separated.