package org.timpeng.chatbot.auth

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val meterRegistry: MeterRegistry,
) {

    fun register(email: String, password: String): UserResponse {
        if (userRepository.existsByEmail(email)) {
            throw UserAlreadyExistsException("Email already registered: $email")
        }
        // PasswordEncoder.encode is @Nullable per its JSpecify-annotated signature (no
        // implementation this app uses actually returns null), but the ?: keeps that contract
        // honest instead of silently trusting a !!.
        val passwordHash = passwordEncoder.encode(password)
            ?: throw IllegalStateException("PasswordEncoder returned a null hash")
        val user = userRepository.save(User(email = email, passwordHash = passwordHash))
        return UserResponse(user.id!!, user.email, user.createdAt)
    }

    fun login(email: String, password: String): AuthResponse {
        val sample = Timer.start(meterRegistry)
        try {
            val user = userRepository.findByEmail(email)
                .orElseThrow { BadCredentialsException("Invalid email or password") }

            if (!passwordEncoder.matches(password, user.passwordHash)) {
                throw BadCredentialsException("Invalid email or password")
            }

            val issued = jwtService.issue(user.id!!)
            recordLoginTime(sample, outcome = "success")
            return AuthResponse(issued.token, issued.expiresAt)
        } catch (e: BadCredentialsException) {
            recordLoginTime(sample, outcome = "bad_credentials")
            throw e
        }
    }

    // Same outcome-tagged-timer convention as history.cache.time / llm.generate.time (see
    // CLAUDE.md's "Metrics" section) rather than a bare duration.
    private fun recordLoginTime(sample: Timer.Sample, outcome: String) {
        sample.stop(
            Timer.builder("auth.login.time")
                .description("登入耗時")
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        )
    }
}
