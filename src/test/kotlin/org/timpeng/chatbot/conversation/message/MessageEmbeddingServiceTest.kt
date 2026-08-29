package org.timpeng.chatbot.conversation.message

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.rag.embedding.EmbeddingProvider
import java.util.Optional

class MessageEmbeddingServiceTest {
    private val embeddingProvider: EmbeddingProvider = mockk()
    private val messageEmbeddingRepository: MessageEmbeddingJdbcRepository = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk()

    private val service = MessageEmbeddingService(embeddingProvider, messageEmbeddingRepository, userRepository)

    private fun message(ownerId: Long?, content: String = "hello") = Message(
        id = 1L,
        conversation = Conversation(id = 1L, uuid = "conv-uuid", ownerId = ownerId),
        role = Role.USER,
        content = content,
    )

    private fun user(enabled: Boolean) =
        User(id = 1L, email = "a@example.com", passwordHash = "hash", messageEmbeddingEnabled = enabled)

    @Test
    fun `embedAsync does nothing for a pre-auth conversation with no owner`() {
        service.embedAsync(message(ownerId = null))

        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `embedAsync does nothing when no embedding provider is configured`() {
        val serviceWithNoProvider =
            MessageEmbeddingService(embeddingProvider = null, messageEmbeddingRepository, userRepository)

        serviceWithNoProvider.embedAsync(message(ownerId = 1L))

        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `embedAsync does nothing when the owner has the switch off`() {
        every { userRepository.findById(1L) } returns Optional.of(user(enabled = false))

        service.embedAsync(message(ownerId = 1L))

        verify(exactly = 0) { messageEmbeddingRepository.updateEmbedding(any(), any()) }
    }

    @Test
    fun `embedAsync does nothing when the owner row is missing`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        service.embedAsync(message(ownerId = 1L))

        verify(exactly = 0) { messageEmbeddingRepository.updateEmbedding(any(), any()) }
    }

    @Test
    fun `embedAsync embeds and persists when the owner has the switch on`() {
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)
        every { userRepository.findById(1L) } returns Optional.of(user(enabled = true))
        coEvery { embeddingProvider.embed("hello") } returns vector

        service.embedAsync(message(ownerId = 1L, content = "hello"))

        // The embed+persist happens on a background CoroutineScope (see the class kdoc), so
        // verify with a timeout rather than immediately after the call returns.
        verify(timeout = 1000) { messageEmbeddingRepository.updateEmbedding(1L, vector) }
    }

    @Test
    fun `embedAsync swallows embedding provider failures instead of persisting`() {
        every { userRepository.findById(1L) } returns Optional.of(user(enabled = true))
        coEvery { embeddingProvider.embed(any()) } throws RuntimeException("boom")

        service.embedAsync(message(ownerId = 1L))

        // Waits for the background coroutine to actually run before asserting the negative —
        // updateEmbedding is only reachable after embed() returns, so once embed() has run,
        // it's settled whether updateEmbedding was ever going to be called.
        coVerify(timeout = 1000) { embeddingProvider.embed(any()) }
        verify(exactly = 0) { messageEmbeddingRepository.updateEmbedding(any(), any()) }
    }
}
