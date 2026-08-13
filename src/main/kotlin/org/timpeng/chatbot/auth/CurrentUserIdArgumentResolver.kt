package org.timpeng.chatbot.auth

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.timpeng.chatbot.config.WebConfig
import org.timpeng.chatbot.filter.JwtAuthenticationFilter

/**
 * Resolves `@CurrentUserId` parameters from whatever [JwtAuthenticationFilter] put in the
 * `SecurityContext`. Registered via [WebConfig]. Throwing when the
 * principal is missing/not a `Long` is deliberate: every endpoint that declares this parameter is
 * also behind `SecurityConfig`'s `authenticated()` rule, so hitting this path means that
 * invariant broke, not that the caller sent a bad request.
 */
@Component
class CurrentUserIdArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        return SecurityContextHolder.getContext().authentication?.principal as? Long
            ?: throw IllegalStateException(
                "@CurrentUserId parameter but no authenticated userId in SecurityContext — " +
                    "is this endpoint missing from SecurityConfig's authenticated() rule?"
            )
    }
}
