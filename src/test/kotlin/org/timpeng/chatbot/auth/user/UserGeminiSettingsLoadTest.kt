package org.timpeng.chatbot.auth.user

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for "Parameter specified as non-null is null: ...UserResponse.<init>,
 * parameter geminiSettings" (and the equivalent NullPointerException from User.<init> via
 * `.copy()`) — a real JPA/Hibernate quirk, not something mockk-backed unit tests can see:
 * Hibernate collapses an `@Embedded` value to a literal `null` when every column it maps to is
 * NULL in the row, even though `User.geminiSettings` is a non-nullable Kotlin type with a
 * `GeminiSettings()` default. That default only runs when *constructing* a `User` in application
 * code (e.g. registration); Hibernate reconstructing one from a ResultSet bypasses it entirely.
 * Any user who never called `PATCH /api/users/me/gemini-settings` — i.e. almost every real user —
 * hits this on the very next read, which is why this needs real Postgres, not `@DataJpaTest`'s
 * in-memory DB or a mocked `UserRepository`.
 *
 * The fix lives in `UserService.requireUser` and `UserResponse.from` (`?: GeminiSettings()`, which
 * genuinely performs a runtime null check regardless of the operand's declared nullability).
 */
@SpringBootTest
class UserGeminiSettingsLoadTest(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val userService: UserService,
) {

    private var insertedId: Long? = null

    private fun freshlySavedUser(email: String): User {
        val saved = userRepository.save(User(email = email, passwordHash = "hashed"))
        insertedId = saved.id
        return saved
    }

    @AfterEach
    fun cleanUp() {
        insertedId?.let { userRepository.deleteById(it) }
    }

    @Test
    fun `Hibernate collapses an all-null embedded GeminiSettings to a literal null on reload`() {
        val saved = freshlySavedUser("gemini-settings-quirk-test@example.com")

        // A separate findById (not save()'s own returned instance) is what actually exercises
        // Hibernate reading the row back from Postgres, rather than the persistence-context cache.
        val reloaded = userRepository.findById(saved.id!!).orElseThrow()

        assertNull(reloaded.geminiSettings)
    }

    @Test
    fun `getUser returns a real GeminiSettings for a user who never set one`() {
        val saved = freshlySavedUser("gemini-settings-getuser-test@example.com")

        val result = userService.getUser(saved.id!!)

        assertEquals(GeminiSettings(), result.geminiSettings)
    }

    @Test
    fun `setMessageEmbeddingEnabled does not crash for a user with no gemini settings`() {
        val saved = freshlySavedUser("gemini-settings-embedding-test@example.com")

        val result = userService.setMessageEmbeddingEnabled(saved.id!!, true)

        assertTrue(result.messageEmbeddingEnabled)
        assertEquals(GeminiSettings(), result.geminiSettings)
    }

    @Test
    fun `updateGeminiApiKey does not crash for a user with no gemini settings`() {
        val saved = freshlySavedUser("gemini-settings-apikey-test@example.com")

        val result = userService.updateGeminiApiKey(saved.id!!, UpdateGeminiApiKeyRequest(apiKey = "sk-test-key"))

        assertTrue(result.hasGeminiApiKey)
        assertEquals(GeminiSettings(), result.geminiSettings)
    }
}
