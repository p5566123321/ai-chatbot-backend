package org.timpeng.chatbot.auth.jwt

import io.jsonwebtoken.Claims
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

    /**
     * Verifies signature and expiry and returns the token's claims, or null for anything
     * missing/expired/malformed/forged. `ExpiredJwtException` doesn't need its own catch clause —
     * it's already a `JwtException` subtype, so it's covered by the same branch.
     */
    fun validateToken(token: String): Claims? {
        return try {
            parseClaims(token)
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Never returns null itself — only ever a parsed Claims or a thrown JwtException/
    // IllegalArgumentException, which validateToken (its only caller) turns into null.
    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

    /**
     * Extracts the userId a token was issued for — see [issue]'s `subject(userId.toString())`.
     * `?.` guards against [Claims.getSubject] returning null (a validly-signed token that, for
     * whatever reason, was issued without a `sub` claim) — without it this is a Kotlin platform
     * type, so a null subject would NPE inside `toLongOrNull()` instead of falling through to a
     * clean null like every other "not a valid userId" case here.
     */
    fun parseUserId(claims: Claims): Long? = claims.subject?.toLongOrNull()
}
