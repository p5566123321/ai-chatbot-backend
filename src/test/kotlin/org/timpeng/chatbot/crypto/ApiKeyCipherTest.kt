package org.timpeng.chatbot.crypto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApiKeyCipherTest {

    private val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val cipher = ApiKeyCipher(validKey)

    @Test
    fun `encrypt then decrypt round-trips the original plaintext`() {
        val ciphertext = cipher.encrypt("sk-real-gemini-key")

        assertEquals("sk-real-gemini-key", cipher.decrypt(ciphertext))
    }

    @Test
    fun `encrypt never reuses the same IV, so ciphertext differs across calls`() {
        val first = cipher.encrypt("sk-real-gemini-key")
        val second = cipher.encrypt("sk-real-gemini-key")

        assertNotEquals(first, second)
        // Both still decrypt to the same plaintext despite the differing ciphertext.
        assertEquals(cipher.decrypt(first), cipher.decrypt(second))
    }

    @Test
    fun `decrypt rejects tampered ciphertext via the GCM auth tag`() {
        val ciphertext = cipher.encrypt("sk-real-gemini-key")
        val bytes = Base64.getDecoder().decode(ciphertext)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)

        assertThrows<Exception> { cipher.decrypt(tampered) }
    }

    @Test
    fun `constructor rejects a key that doesn't decode to 32 bytes`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))

        assertThrows<IllegalArgumentException> { ApiKeyCipher(shortKey) }
    }
}
