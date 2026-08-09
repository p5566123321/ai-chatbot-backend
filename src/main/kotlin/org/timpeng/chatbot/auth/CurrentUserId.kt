package org.timpeng.chatbot.auth

/**
 * Marks a controller method parameter to be resolved from the authenticated caller's `userId`
 * (see [CurrentUserIdArgumentResolver]) rather than the request itself — the JWT-derived
 * equivalent of `@AuthenticationPrincipal`, specialized for this app since the principal is a
 * bare `Long`, not a `UserDetails`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
