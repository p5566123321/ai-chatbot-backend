package org.timpeng.chatbot.crypto

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM at-rest encryption for user-supplied Gemini API keys (BYOK, docs/decision/010) —
 * `User.geminiApiKeyCiphertext`. The signing/encryption key is derived once at construction from
 * `app.byok.encryption-key` (env `BYOK_ENCRYPTION_KEY`, generate with `openssl rand -base64 32`):
 * it must decode to exactly 32 bytes, and a bad length is deliberately allowed to blow up
 * application startup rather than silently encrypting with a truncated/padded key — same
 * fail-fast reasoning as [org.timpeng.chatbot.auth.jwt.JwtService] validating `app.jwt.secret` via
 * `Keys.hmacShaKeyFor`.
 *
 * Storage format is `Base64(iv || ciphertext+tag)`: a fresh random 12-byte GCM IV is generated per
 * [encrypt] call (GCM requires a unique IV per encryption under the same key) and prepended to the
 * output, so [decrypt] can recover it without a separate column.
 */
@Service
class ApiKeyCipher(
    @Value($$"${app.byok.encryption-key}") rawKey: String,
) {
    private val secureRandom = SecureRandom()
    private val key: SecretKeySpec

    init {
        val decoded = Base64.getDecoder().decode(rawKey)
        require(decoded.size == KEY_SIZE_BYTES) {
            "app.byok.encryption-key must decode to $KEY_SIZE_BYTES bytes (AES-256) for BYOK " +
                "encryption, got ${decoded.size}"
        }
        key = SecretKeySpec(decoded, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(stored: String): String {
        val bytes = Base64.getDecoder().decode(stored)
        require(bytes.size > IV_SIZE_BYTES) { "malformed BYOK ciphertext" }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BYTES = 32
        private const val IV_SIZE_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
