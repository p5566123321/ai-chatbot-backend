package org.timpeng.chatbot.auth

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.dao.DataIntegrityViolationException
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

    // A password hash to run the BCrypt comparison against when the email lookup misses, so an
    // unknown email pays the same ~100ms BCrypt cost a known email always pays — see login()'s
    // comment. Computed once at construction, not per-request.
    private val dummyPasswordHash: String = passwordEncoder.encode("dummy-password-for-timing-safety")
        ?: throw IllegalStateException("PasswordEncoder returned a null hash")

    fun register(email: String, password: String): UserResponse {
        if (userRepository.existsByEmail(email)) {
            throw UserAlreadyExistsException("Email already registered: $email")
        }
        // PasswordEncoder.encode is @Nullable per its JSpecify-annotated signature (no
        // implementation this app uses actually returns null), but the ?: keeps that contract
        // honest instead of silently trusting a !!.
        val passwordHash = passwordEncoder.encode(password)
            ?: throw IllegalStateException("PasswordEncoder returned a null hash")
        // The existsByEmail check above is TOCTOU-racy: two concurrent registrations for the same
        // email can both pass it before either save() commits. The unique index on User.email is
        // the real guard; a losing save() surfaces here as DataIntegrityViolationException, which
        // we translate to the same UserAlreadyExistsException the pre-check throws, rather than
        // letting it fall through to GlobalExceptionHandler's generic 500 (which would leak the
        // raw DB constraint message to the client).
        val user = try {
            userRepository.save(User(email = email, passwordHash = passwordHash))
        } catch (e: DataIntegrityViolationException) {
            throw UserAlreadyExistsException("Email already registered: $email")
        }
        return UserResponse(user.id!!, user.email, user.createdAt)
    }

    fun login(email: String, password: String): AuthResponse {
        val sample = Timer.start(meterRegistry)
        try {
            val user = userRepository.findByEmail(email).orElse(null)

            // Always run the BCrypt comparison, even on an unknown email (against
            // dummyPasswordHash) — an early return here would make login measurably faster for
            // unregistered emails than registered ones, letting response timing enumerate which
            // emails have accounts even though the status/body are identical either way.
            val passwordMatches = passwordEncoder.matches(password, user?.passwordHash ?: dummyPasswordHash)

            if (user == null || !passwordMatches) {
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
