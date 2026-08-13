package org.timpeng.chatbot.auth.jwt

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `app.jwt.*` settings (ADR-007) — one injectable holder rather than repeating `@Value` at every
 * call site, mirroring [org.timpeng.chatbot.queue.QueueProperties]. `secret` has no default: an
 * unset `JWT_SECRET` should fail application startup, not silently sign tokens with a guessable
 * key.
 */
@Component
class JwtProperties(
    @Value($$"${app.jwt.secret}") val secret: String,
    @Value($$"${app.jwt.issuer}") val issuer: String,
    @Value($$"${app.jwt.expiration-ms}") val expirationMs: Long,
)
