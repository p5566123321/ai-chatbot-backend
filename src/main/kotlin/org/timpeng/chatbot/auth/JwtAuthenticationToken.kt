package org.timpeng.chatbot.auth

import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * The [org.springframework.security.core.Authentication] [JwtAuthenticationFilter] puts in the
 * `SecurityContext` on a valid bearer token. No roles/permissions exist yet (ADR-007), so the
 * principal is just the caller's `userId` rather than a full `UserDetails` — controllers read it
 * back via [CurrentUserId].
 */
class JwtAuthenticationToken(private val userId: Long) : AbstractAuthenticationToken(emptyList()) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Long = userId
}
