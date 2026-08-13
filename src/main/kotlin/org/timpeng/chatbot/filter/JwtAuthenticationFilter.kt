package org.timpeng.chatbot.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.timpeng.chatbot.auth.jwt.JwtAuthenticationToken
import org.timpeng.chatbot.auth.jwt.JwtService

private const val BEARER_PREFIX = "Bearer "

/**
 * Populates `SecurityContext` from an `Authorization: Bearer <token>` header. Never rejects a
 * request itself — an absent/invalid token just leaves the context empty, and `SecurityConfig`'s
 * `authenticated()` rule (or `permitAll()`) is what actually decides whether that's acceptable
 * for the endpoint being hit. Runs once per request (`OncePerRequestFilter`), same guarantee
 * every other filter in this chain relies on.
 *
 * Deliberately **not** a `@Component`: Spring Boot auto-registers every `Filter` bean as a global
 * servlet filter (via a `FilterRegistrationBean`) on top of whatever `HttpSecurity.addFilterBefore`
 * wires into the security chain, which would run this twice per request; it also makes
 * `@WebMvcTest` slices auto-detect and try to construct it outside the security chain, pulling in
 * `JwtService` (a `@Service`, out of scope for a controller slice) as an unsatisfied dependency.
 * `SecurityConfig` instantiates this directly instead, exactly once, only inside the real chain.
 *
 * Must also run on ASYNC/ERROR dispatch, not just the initial REQUEST one:
 * `OncePerRequestFilter` skips both by default (`shouldNotFilterAsyncDispatch`/
 * `shouldNotFilterErrorDispatch` default to `true`), on the assumption that whatever the first
 * pass put in `SecurityContextHolder` is still there for the re-dispatch. That assumption doesn't
 * hold here: this app is stateless (no `HttpSession`, no `SecurityContextRepository` persisting
 * it), so the context is empty again by the time the container re-enters the filter chain for the
 * ASYNC dispatch a long-lived `SseEmitter` (`ChatController.streamChat`) triggers on completion —
 * `AuthorizationFilter` then sees an anonymous request and rejects it with
 * `AuthorizationDeniedException`, which blows up trying to write a 401 onto an SSE response whose
 * headers are already committed (`ExceptionTranslationFilter`'s "response is already committed").
 * Re-running this filter on those dispatch types re-parses the same `Authorization` header (still
 * present on the re-dispatched request) and refills the context, which is cheap and side-effect
 * free.
 */
class JwtAuthenticationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    override fun shouldNotFilterAsyncDispatch() = false

    override fun shouldNotFilterErrorDispatch() = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            val token = header.removePrefix(BEARER_PREFIX)
            val userId = jwtService.validateToken(token)?.let { jwtService.parseUserId(it) }

            if (userId != null) {
                SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(userId)
            }
        }

        filterChain.doFilter(request, response)
    }
}
