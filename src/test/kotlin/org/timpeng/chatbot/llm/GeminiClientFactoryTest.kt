package org.timpeng.chatbot.llm

import com.google.genai.Models
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.crypto.ApiKeyCipher
import kotlin.test.assertSame

class GeminiClientFactoryTest {

    private val defaultModels: Models = mockk()
    private val apiKeyCipher: ApiKeyCipher = mockk()
    private val factory = GeminiClientFactory(defaultModels, apiKeyCipher)

    private fun user(geminiApiKeyCiphertext: String? = null) = User(
        id = 1L,
        email = "user@example.com",
        passwordHash = "hashed",
        geminiApiKeyCiphertext = geminiApiKeyCiphertext,
    )

    @Test
    fun `modelsFor returns the default Models when the user is null`() {
        assertSame(defaultModels, factory.modelsFor(null))
    }

    @Test
    fun `modelsFor returns the default Models when the user has no BYOK key`() {
        assertSame(defaultModels, factory.modelsFor(user()))
    }

    @Test
    fun `modelsFor decrypts the stored ciphertext when the user has a BYOK key`() {
        every { apiKeyCipher.decrypt("ciphertext") } returns "sk-real-key"

        val result = factory.modelsFor(user(geminiApiKeyCiphertext = "ciphertext"))

        verify { apiKeyCipher.decrypt("ciphertext") }
        assert(result !== defaultModels) { "expected a fresh per-user Models, not the default bean" }
    }
}
