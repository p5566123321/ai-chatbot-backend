package org.timpeng.chatbot.auth.user

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.timpeng.chatbot.crypto.ApiKeyCipher
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserServiceTest {
    private val userRepository: UserRepository = mockk()
    private val apiKeyCipher: ApiKeyCipher = mockk()
    private val userService = UserService(userRepository, apiKeyCipher)

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

    @Test
    fun `updateGeminiSettings replaces the caller's settings and persists them`() {
        val slot = slot<User>()
        val request = UpdateGeminiSettingsRequest(
            systemInstruction = "Be concise",
            temperature = 0.7f,
            topP = 0.9f,
            topK = 40f,
            candidateCount = 1,
            maxOutputTokens = 2048,
        )
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        val result = userService.updateGeminiSettings(1L, request)

        assertEquals(request.toSettings(), slot.captured.geminiSettings)
        assertEquals(request.toSettings(), result.geminiSettings)
    }

    @Test
    fun `updateGeminiSettings with an empty request clears previous overrides`() {
        val slot = slot<User>()
        val userWithSettings = user.copy(geminiSettings = GeminiSettings(temperature = 0.9f))
        every { userRepository.findById(1L) } returns Optional.of(userWithSettings)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        userService.updateGeminiSettings(1L, UpdateGeminiSettingsRequest())

        assertEquals(GeminiSettings(), slot.captured.geminiSettings)
    }

    @Test
    fun `updateGeminiSettings throws when the authenticated userId has no row`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        assertThrows<IllegalStateException> {
            userService.updateGeminiSettings(1L, UpdateGeminiSettingsRequest())
        }
    }

    @Test
    fun `updateGeminiSettings accepts a whitelisted model`() {
        val slot = slot<User>()
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        userService.updateGeminiSettings(1L, UpdateGeminiSettingsRequest(model = "gemini-2.5-pro"))

        assertEquals("gemini-2.5-pro", slot.captured.geminiSettings.model)
    }

    @Test
    fun `updateGeminiSettings rejects a model outside the whitelist`() {
        every { userRepository.findById(1L) } returns Optional.of(user)

        assertThrows<IllegalArgumentException> {
            userService.updateGeminiSettings(1L, UpdateGeminiSettingsRequest(model = "not-a-real-model"))
        }
    }

    @Test
    fun `updateGeminiApiKey encrypts and stores a non-blank key`() {
        val slot = slot<User>()
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(capture(slot)) } answers { slot.captured }
        every { apiKeyCipher.encrypt("sk-real-key") } returns "ciphertext"

        val result = userService.updateGeminiApiKey(1L, UpdateGeminiApiKeyRequest(apiKey = "sk-real-key"))

        assertEquals("ciphertext", slot.captured.geminiApiKeyCiphertext)
        assertTrue(result.hasGeminiApiKey)
    }

    @Test
    fun `updateGeminiApiKey clears the stored key when apiKey is null`() {
        val slot = slot<User>()
        val userWithKey = user.copy(geminiApiKeyCiphertext = "ciphertext")
        every { userRepository.findById(1L) } returns Optional.of(userWithKey)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        val result = userService.updateGeminiApiKey(1L, UpdateGeminiApiKeyRequest(apiKey = null))

        assertNull(slot.captured.geminiApiKeyCiphertext)
        assertFalse(result.hasGeminiApiKey)
    }

    @Test
    fun `updateGeminiApiKey clears the stored key when apiKey is blank`() {
        val slot = slot<User>()
        val userWithKey = user.copy(geminiApiKeyCiphertext = "ciphertext")
        every { userRepository.findById(1L) } returns Optional.of(userWithKey)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        userService.updateGeminiApiKey(1L, UpdateGeminiApiKeyRequest(apiKey = "   "))

        assertNull(slot.captured.geminiApiKeyCiphertext)
    }

    @Test
    fun `updateGeminiApiKey throws when the authenticated userId has no row`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        assertThrows<IllegalStateException> {
            userService.updateGeminiApiKey(1L, UpdateGeminiApiKeyRequest(apiKey = "sk-real-key"))
        }
    }

    @Test
    fun `setRagEnabled flips the switch and persists it`() {
        val slot = slot<User>()
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(capture(slot)) } answers { slot.captured }

        val result = userService.setRagEnabled(1L, false)

        assertFalse(slot.captured.ragEnabled)
        assertFalse(result.ragEnabled)
    }

    @Test
    fun `setRagEnabled throws when the authenticated userId has no row`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        assertThrows<IllegalStateException> { userService.setRagEnabled(1L, false) }
    }
}
