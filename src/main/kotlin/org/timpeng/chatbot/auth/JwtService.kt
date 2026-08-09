package org.timpeng.chatbot.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

data class IssuedToken(val token: String, val expiresAt: Instant)

/**
 * Signs and verifies access tokens (ADR-007 — access-token-only, no refresh). The signing key is
 * derived once from `app.jwt.secret` at construction time via [Keys.hmacShaKeyFor], which throws
 * [io.jsonwebtoken.security.WeakKeyException] for anything shorter than 256 bits for HS256 — that
 * failure is deliberately allowed to blow up application startup rather than silently signing
 * tokens with a guessable key (see `.env.example`'s `openssl rand -base64 64` suggestion).
 */
@Service
class JwtService(jwtProperties: JwtProperties) {

    private val issuer = jwtProperties.issuer
    private val expirationMs = jwtProperties.expirationMs
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    fun issue(userId: Long): IssuedToken {
        val now = Instant.now()
        val expiresAt = now.plusMillis(expirationMs)
        val token = Jwts.builder()
            .issuer(issuer)
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return IssuedToken(token, expiresAt)
    }

    /** Returns the authenticated userId, or null for a missing/expired/malformed/forged token. */
    fun parseUserId(token: String): Long? {
        return try {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            claims.subject.toLongOrNull()
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
