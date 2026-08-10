package org.timpeng.chatbot.auth

import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 64+ chars so Keys.hmacShaKeyFor accepts it as a valid HS256 key (>= 256 bits) — see
// JwtService's kdoc on why a too-short secret is meant to fail loudly instead.
private const val TEST_SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256-abcdefghijklmnop"

class JwtServiceTest {

    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        jwtService = JwtService(JwtProperties(secret = TEST_SECRET, issuer = "test-issuer", expirationMs = 3_600_000))
    }

    @Test
    fun `issue then validateToken round-trips the same userId`() {
        val issued = jwtService.issue(userId = 42L)

        val claims = jwtService.validateToken(issued.token)

        assertEquals(42L, claims?.let { jwtService.parseUserId(it) })
    }

    @Test
    fun `validateToken returns null for a malformed token`() {
        assertNull(jwtService.validateToken("not-a-jwt-at-all"))
    }

    @Test
    fun `validateToken returns null for an expired token`() {
        val expiredIssuer = JwtService(
            JwtProperties(secret = TEST_SECRET, issuer = "test-issuer", expirationMs = -1_000)
        )
        val alreadyExpired = expiredIssuer.issue(userId = 1L)

        assertNull(jwtService.validateToken(alreadyExpired.token))
    }

    @Test
    fun `validateToken returns null for a token signed with a different key`() {
        val otherService = JwtService(
            JwtProperties(secret = "a-completely-different-secret-key-also-at-least-32-bytes-long", issuer = "test-issuer", expirationMs = 3_600_000)
        )
        val forged = otherService.issue(userId = 1L)

        assertNull(jwtService.validateToken(forged.token))
    }

    @Test
    fun `parseUserId returns null when the subject claim isn't a valid Long`() {
        val claims = Jwts.claims().subject("not-a-number").build()

        assertNull(jwtService.parseUserId(claims))
    }

    @Test
    fun `parseUserId returns null when there is no subject claim at all`() {
        val claims = Jwts.claims().build()

        assertNull(jwtService.parseUserId(claims))
    }
}
