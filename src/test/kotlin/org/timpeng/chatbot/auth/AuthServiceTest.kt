package org.timpeng.chatbot.auth

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.timpeng.chatbot.auth.jwt.IssuedToken
import org.timpeng.chatbot.auth.jwt.JwtService
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.exception.UserAlreadyExistsException
import org.timpeng.chatbot.auth.user.UserRepository
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals

class AuthServiceTest {
    private val userRepository: UserRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val jwtService: JwtService = mockk()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var authService: AuthService

    private val email = "user@example.com"
    private val password = "hunter2"
    private val passwordHash = "hashed-password"
    private val dummyHash = "dummy-hash"

    @BeforeEach
    fun setUp() {
        // AuthService computes its timing-safety dummy hash at construction time (see login()).
        every { passwordEncoder.encode("dummy-password-for-timing-safety") } returns dummyHash
        authService = AuthService(userRepository, passwordEncoder, jwtService, meterRegistry)
    }

    // register

    @Test
    fun `register saves a new user and returns it`() {
        every { userRepository.existsByEmail(email) } returns false
        every { passwordEncoder.encode(password) } returns passwordHash
        val slot = slot<User>()
        every { userRepository.save(capture(slot)) } answers {
            slot.captured.copy(id = 1L)
        }

        val result = authService.register(email, password)

        assertEquals(1L, result.id)
        assertEquals(email, result.email)
        assertEquals(email, slot.captured.email)
        assertEquals(passwordHash, slot.captured.passwordHash)
    }

    @Test
    fun `register throws UserAlreadyExistsException when existsByEmail pre-check finds a match`() {
        every { userRepository.existsByEmail(email) } returns true

        assertThrows<UserAlreadyExistsException> { authService.register(email, password) }

        verify(exactly = 0) { userRepository.save(any()) }
    }

    // TOCTOU: existsByEmail can race with a concurrent registration for the same email; the
    // unique index on User.email is the real guard, enforced at save() time.
    @Test
    fun `register translates a unique-constraint violation on save into UserAlreadyExistsException`() {
        every { userRepository.existsByEmail(email) } returns false
        every { passwordEncoder.encode(password) } returns passwordHash
        every { userRepository.save(any()) } throws DataIntegrityViolationException("duplicate key value violates unique constraint")

        assertThrows<UserAlreadyExistsException> { authService.register(email, password) }
    }

    @Test
    fun `register normalizes email to lowercase and trimmed before checking existence and saving`() {
        every { userRepository.existsByEmail(email) } returns false
        every { passwordEncoder.encode(password) } returns passwordHash
        val slot = slot<User>()
        every { userRepository.save(capture(slot)) } answers { slot.captured.copy(id = 1L) }

        val result = authService.register("  User@Example.com  ", password)

        assertEquals(email, result.email)
        assertEquals(email, slot.captured.email)
        verify(exactly = 1) { userRepository.existsByEmail(email) }
    }

    // login

    @Test
    fun `login returns a token for correct credentials`() {
        val user = User(id = 1L, email = email, passwordHash = passwordHash, createdAt = LocalDateTime.now())
        every { userRepository.findByEmail(email) } returns Optional.of(user)
        every { passwordEncoder.matches(password, passwordHash) } returns true
        every { jwtService.issue(1L) } returns IssuedToken("signed-token", Instant.now())

        val result = authService.login(email, password)

        assertEquals("signed-token", result.token)
    }

    @Test
    fun `login normalizes email to lowercase and trimmed before lookup`() {
        val user = User(id = 1L, email = email, passwordHash = passwordHash, createdAt = LocalDateTime.now())
        every { userRepository.findByEmail(email) } returns Optional.of(user)
        every { passwordEncoder.matches(password, passwordHash) } returns true
        every { jwtService.issue(1L) } returns IssuedToken("signed-token", Instant.now())

        val result = authService.login("  User@Example.com  ", password)

        assertEquals("signed-token", result.token)
        verify(exactly = 1) { userRepository.findByEmail(email) }
    }

    @Test
    fun `login throws BadCredentialsException for an unknown email`() {
        every { userRepository.findByEmail(email) } returns Optional.empty()
        every { passwordEncoder.matches(password, dummyHash) } returns false

        assertThrows<BadCredentialsException> { authService.login(email, password) }
    }

    // Timing side-channel: an early return on a lookup miss (skipping the BCrypt comparison
    // entirely) would make login measurably faster for unregistered emails, letting response
    // timing enumerate which emails have accounts. Asserting the dummy-hash comparison actually
    // runs is what pins that behavior down, not just the exception type above.
    @Test
    fun `login runs the BCrypt comparison against a dummy hash even for an unknown email`() {
        every { userRepository.findByEmail(email) } returns Optional.empty()
        every { passwordEncoder.matches(password, dummyHash) } returns false

        assertThrows<BadCredentialsException> { authService.login(email, password) }

        verify(exactly = 1) { passwordEncoder.matches(password, dummyHash) }
    }

    @Test
    fun `login throws BadCredentialsException for a wrong password`() {
        val user = User(id = 1L, email = email, passwordHash = passwordHash, createdAt = LocalDateTime.now())
        every { userRepository.findByEmail(email) } returns Optional.of(user)
        every { passwordEncoder.matches(password, passwordHash) } returns false

        assertThrows<BadCredentialsException> { authService.login(email, password) }
    }
}
