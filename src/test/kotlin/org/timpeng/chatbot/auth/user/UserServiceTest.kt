package org.timpeng.chatbot.auth.user

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserServiceTest {
    private val userRepository: UserRepository = mockk()
    private val userService = UserService(userRepository)

    private val user = User(
        id = 1L,
        email = "user@example.com",
        passwordHash = "hashed",
        createdAt = LocalDateTime.now(),
        messageEmbeddingEnabled = false,
    )

    @Test
    fun `getUser returns the caller's account`() {
        every { userRepository.findById(1L) } returns Optional.of(user)

        val result = userService.getUser(1L)

        assertEquals(user.email, result.email)
        assertFalse(result.messageEmbeddingEnabled)
    }

    @Test
    fun `getUser throws when the authenticated userId has no row`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        assertThrows<IllegalStateException> { userService.getUser(1L) }
    }

    @Test
    fun `setMessageEmbeddingEnabled flips the switch and persists it`() {
        val slot = slot<User>()
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        val result = userService.setMessageEmbeddingEnabled(1L, true)

        assertTrue(slot.captured.messageEmbeddingEnabled)
        assertTrue(result.messageEmbeddingEnabled)
    }

    @Test
    fun `setMessageEmbeddingEnabled throws when the authenticated userId has no row`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        assertThrows<IllegalStateException> { userService.setMessageEmbeddingEnabled(1L, true) }
    }
}
