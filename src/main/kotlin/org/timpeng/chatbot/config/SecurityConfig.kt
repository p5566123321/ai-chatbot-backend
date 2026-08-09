package org.timpeng.chatbot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.timpeng.chatbot.auth.JwtAuthenticationFilter
import org.timpeng.chatbot.auth.JwtService
import tools.jackson.databind.ObjectMapper
import org.timpeng.chatbot.global.ErrorResponse

/**
 * Stateless JWT auth (ADR-007). No CSRF (no cookie-based session — tokens travel in the
 * `Authorization` header, which isn't automatically replayed cross-site the way a cookie is), no
 * `HttpSession` (`SessionCreationPolicy.STATELESS`; the app never creates one, see the removal of
 * `RedisSessionConfig` in this same change). `JwtAuthenticationFilter` runs ahead of Spring
 * Security's own `UsernamePasswordAuthenticationFilter` slot — unused here since there's no
 * form login, just the anchor point every tutorial/example places a custom auth filter at — to
 * populate the `SecurityContext` before the `authorizeHttpRequests` rules below evaluate it. Built
 * here rather than injected as a `@Component` — see `JwtAuthenticationFilter`'s own kdoc for why.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val jwtAuthenticationFilter = JwtAuthenticationFilter(jwtService)

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**").permitAll()
                it.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    response.status = 401
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write(
                        objectMapper.writeValueAsString(
                            ErrorResponse(status = 401, error = "Unauthorized", message = "Missing or invalid access token")
                        )
                    )
                }
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
