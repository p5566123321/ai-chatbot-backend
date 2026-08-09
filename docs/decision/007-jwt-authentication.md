# ADR-007: JWT Authentication

**Status:** Accepted

## Context

The app has had zero authentication since Phase 1. [ADR-002](002-conversation-id.md) and
[ADR-004](004-conversation-id-backend-generated.md) both explicitly deferred it — ADR-002 called
this "a public, anonymous chatbot with no authentication in the MVP" and named the trigger for
revisiting as "if authentication is added in the future"; ADR-004 then executed half of that
trigger (backend-generated `conversationId`) and left a specific seam: "when auth lands,
`POST /api/conversations` is the one place that needs to require a session and stamp an
`ownerId`; no other endpoint shape changes."

That seam turns out to be too narrow. With no `ownerId` on `Conversation` today, anyone who
obtains a `conversationId` (it's a path segment, so it ends up in browser history, proxy/access
logs, referrer headers) can read and continue that conversation — `GET .../messages`,
`POST .../messages/stream`, etc. all trust the ID alone. Adding auth only at creation, as ADR-004
sketched, would stamp an owner but never check it. This ADR treats **every**
`/api/conversations/{conversationId}/...` endpoint as needing an ownership check, not just create.

Separately, `RedisSessionConfig` (`@EnableRedisHttpSession`) and `spring.session.store-type=redis`
have existed since early on but nothing in the codebase ever reads or writes `HttpSession` —
it's unused scaffolding, not a working mechanism. JWT auth is stateless by design, so this ADR
also removes that config rather than leave two auth-adjacent mechanisms in the tree where only
one actually does anything.

Also relevant: `ChatDlqController` (`GET /api/admin/queues/chat/dlq`) was shipped with a comment
that it has "no auth — matches this app's posture everywhere else today... worth revisiting before
this is ever reachable outside a trusted network." This ADR is that revisit's prerequisite, though
not the revisit itself — see "Future considerations."

## Decision

**Self-issued JWTs backed by a new Postgres `User` table**, verified by a `spring-boot-starter-security`
filter chain, stateless (`SessionCreationPolicy.STATELESS`), access-token-only (no refresh token),
and `Conversation.ownerId` enforced on every conversation-scoped endpoint — not just creation.

```
POST /api/auth/register   { email, password }        → 201, { id, email, createdAt }
POST /api/auth/login      { email, password }        → 200, { token, expiresAt }
```

- Passwords hashed with BCrypt (`PasswordEncoder` bean), never stored/logged in plaintext.
- `JwtService` signs a token per login (`sub` = `userId`), `app.jwt.expiration-ms` (default 1h)
  governs expiry — no refresh endpoint, no server-side token store; a client re-authenticates on
  expiry.
- `JwtAuthenticationFilter` (`OncePerRequestFilter`) reads `Authorization: Bearer <token>`, and on
  success populates `SecurityContext` with a lightweight principal carrying just `userId: Long` —
  there's no role/permission concept yet, so a full `UserDetails` load isn't justified.
- `Conversation` gains `ownerId: Long?` (nullable — see "Legacy conversations" below).
  `ConversationService.createConversation()` stamps the caller's `userId`. Every other
  conversation-scoped endpoint loads the conversation and 404s (via the existing
  `ConversationNotFoundException`, **not** a 403) if `ownerId != callerUserId` — matching
  ADR-004's own reasoning for using 404 on unknown IDs: don't let a response distinguish "doesn't
  exist" from "exists but isn't yours."
- `/api/auth/**` and `/actuator/health`, `/actuator/prometheus` stay `permitAll` (the Grafana
  dashboard from the queue-metrics work depends on the latter); everything else under `/api/**`
  requires a valid bearer token.

## Options considered

### User source

| Option | | |
|---|---|---|
| A. Self-built account system (chosen) | ✅ | No external dependency; matches the project's existing pattern of owning its own Postgres/Redis rather than delegating to a managed service |
| | ❌ | This app now owns password storage/hashing correctness, not an IdP |
| B. External IdP (OAuth2/OIDC — Google, Auth0, Keycloak) | ✅ | No password handling in this codebase at all |
| | ❌ | New external dependency + integration surface for a project that doesn't have a login UI or user base to onboard yet |
| C. JWT verification layer only, accounts deferred | ✅ | Smallest slice — proves the filter chain + ownership-check plumbing first |
| | ❌ | Ships a login-less "auth" system with nothing to authenticate against; punts the actual access-control problem this ADR exists to solve |

**Chosen: A.** Nothing today needs federated identity or SSO, and the project's own conventions
(own the Postgres row, own the Redis key) point at owning the `User` row the same way.

### Token strategy

| Option | | |
|---|---|---|
| A. Access token only, no refresh (chosen) | ✅ | Smallest correct implementation — no refresh endpoint, no revocation store, no rotation logic to get wrong first try |
| | ❌ | Session ends abruptly at expiry; re-login required, no silent renewal |
| B. Access + refresh token | ✅ | Better UX (silent renewal), standard practice for longer-lived clients |
| | ❌ | Needs a refresh-token store (Redis, most naturally) to support revocation, a `/api/auth/refresh` endpoint, and frontend changes to use it — meaningfully more surface for a first pass |

**Chosen: A**, matching this project's usual pattern of shipping the smallest correct slice before
layering on (see how ADR-006 explicitly deferred `streamChat` migration and backoff jitter rather
than bundling them). Refresh tokens are the natural Phase 2 of auth if session length becomes a
real complaint.

### Legacy conversations (rows with no owner)

| Option | | |
|---|---|---|
| A. Leave `ownerId` nullable, no backfill (chosen) | ✅ | No migration to write, no guessing which user a pre-auth conversation "belongs to" |
| | ❌ | Every pre-auth conversation becomes permanently unreachable (any `ownerId == null` row 404s for all callers, same as a not-owned row) |
| B. Backfill to a seed/admin user | ✅ | Old data stays reachable, by one account |
| | ❌ | Fabricates an ownership relationship that never existed; this is a demo-stage app, so there's no real user data worth preserving access to |

**Chosen: A.** Consistent with ADR-004's own precedent of treating unresolvable/ambiguous
conversation state as 404 rather than trying to paper over it.

## Rationale

The three decisions above share one thread: do the smallest thing that closes the actual security
gap (unauthenticated read/write of any conversation whose ID leaks) without inventing
infrastructure (external IdP, refresh-token store, data-migration guesswork) this app doesn't need
yet. The ownership check is the part that actually matters; token mechanics are kept deliberately
boring around it.

## Known limitations (stated honestly, not oversold)

- **No refresh token means no server-side revocation lever either** — once issued, a JWT is valid
  until it expires; there's no logout-that-actually-invalidates-the-token. Fine at 1h expiry and
  today's trust level, but a real "log out everywhere" or "I think my token leaked" story needs a
  revocation mechanism (deny-list in Redis, most naturally) that doesn't exist yet.
- **No roles/permissions** — the JWT principal is just `userId`. `ChatDlqController`
  (`/api/admin/queues/chat/dlq`) will pick up "authenticated" for free once this filter chain
  exists, but that's not the same as "admin-only" — any logged-in user could hit it. Closing that
  properly needs a role concept this ADR doesn't build. Flagged, not solved, here.
- **Symmetric signing key (`app.jwt.secret`), single value, no rotation story.** Fine for a
  single-instance deployment; key rotation would need a `kid`-keyed key set before it's a non-event.

## Future considerations

| Item | Description |
|---|---|
| Refresh tokens | If 1h re-login proves annoying in practice — needs a revocable token store (Redis). |
| Roles / admin gate | Minimal `role` claim + a `hasRole("ADMIN")` check, specifically to close the `ChatDlqController` exposure this ADR only partially addresses (authenticated, not admin-gated). |
| Token revocation / logout | Redis deny-list keyed by token `jti`, checked in `JwtAuthenticationFilter` alongside signature/expiry validation. |
| Key rotation | Move from a single `app.jwt.secret` to a `kid`-addressed key set if this ever runs long enough to need it. |

## Addendum: JWT library selection

`jjwt-jackson` (the common companion module to `io.jsonwebtoken:jjwt-api`/`jjwt-impl`) depends on
classic `com.fasterxml.jackson`. This project already carries `tools.jackson.module:jackson-module-kotlin`
— Jackson 3's rebranded coordinates — for its own JSON handling, so pulling in a second, differently
namespaced Jackson major version purely for token (de)serialization risks a dependency conflict for
no benefit (JWT claims are a handful of primitive fields; they don't need the app's main object
mapper). **Decision: `jjwt-api` + `jjwt-impl` + `jjwt-gson`** — Gson has no version relationship
with the app's Jackson stack at all, so this sidesteps the question entirely rather than pinning
and hoping. To be confirmed with a build spike as the first implementation step; if `jjwt-gson`
turns out to have its own issue, `nimbus-jose-jwt` (used internally by Spring's own OAuth2 resource
server support, so guaranteed compatible with this Spring Boot version) is the fallback.
