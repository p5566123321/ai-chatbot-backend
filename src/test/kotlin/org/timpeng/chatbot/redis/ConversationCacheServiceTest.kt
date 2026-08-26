package org.timpeng.chatbot.redis

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.timpeng.chatbot.auth.user.User
import org.timpeng.chatbot.auth.user.UserRepository
import org.timpeng.chatbot.conversation.Conversation
import org.timpeng.chatbot.conversation.message.Message
import org.timpeng.chatbot.conversation.message.Role
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.Optional
import kotlin.test.assertEquals

class ConversationCacheServiceTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val listOps: ListOperations<String, String> = mockk()
    private val objectMapper = ObjectMapper()
    private val userRepository: UserRepository = mockk()
    private lateinit var conversationCacheService: ConversationCacheService

    private val conversationId = "test-uuid"
    private val key = "chat:conversation:$conversationId"
    private val conversation = Conversation(id = 1L, uuid = conversationId, createdAt = FIXED_TIME)
    private val message = Message(
        id = 1L,
        conversation = conversation,
        role = Role.USER,
        content = "Hello",
        createdAt = FIXED_TIME,
    )

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForList() } returns listOps
        conversationCacheService =
            ConversationCacheService(redisTemplate, objectMapper, userRepository, maxMessages = 10, ttlMinutes = 30)
    }

    @Test
    fun `getChatHistory deserializes the cached json list under the conversation key`() {
        val json = objectMapper.writeValueAsString(message)
        every { listOps.range(key, 0, -1) } returns listOf(json)

        val result = conversationCacheService.getChatHistory(conversationId)

        assertEquals(listOf(message), result)
    }

    @Test
    fun `getChatHistory returns an empty list when the key does not exist`() {
        every { listOps.range(key, 0, -1) } returns null

        val result = conversationCacheService.getChatHistory(conversationId)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `saveChatMessage RPUSHes the serialized message, trims to max size, and refreshes the ttl`() {
        val callback = slot<RedisCallback<Any?>>()
        every { redisTemplate.executePipelined(capture(callback)) } answers {
            callback.captured.doInRedis(mockk(relaxed = true))
            emptyList()
        }
        every { listOps.rightPush(key, any()) } returns 1L
        every { listOps.trim(key, -10L, -1L) } just Runs
        every { redisTemplate.expire(key, Duration.ofMinutes(30)) } returns true

        conversationCacheService.saveChatMessage(conversationId, message)

        verify { listOps.rightPush(key, objectMapper.writeValueAsString(message)) }
        verify { listOps.trim(key, -10L, -1L) }
        verify { redisTemplate.expire(key, Duration.ofMinutes(30)) }
    }

    @Test
    fun `saveChatMessage trims to the owner's history-max-messages override instead of the global default`() {
        val ownerId = 42L
        val ownedConversation = conversation.copy(ownerId = ownerId)
        val ownedMessage = message.copy(conversation = ownedConversation)
        every { userRepository.findById(ownerId) } returns Optional.of(
            User(id = ownerId, email = "user@example.com", passwordHash = "hashed", historyMaxMessages = 3)
        )
        val callback = slot<RedisCallback<Any?>>()
        every { redisTemplate.executePipelined(capture(callback)) } answers {
            callback.captured.doInRedis(mockk(relaxed = true))
            emptyList()
        }
        every { listOps.rightPush(key, any()) } returns 1L
        every { listOps.trim(key, -3L, -1L) } just Runs
        every { redisTemplate.expire(key, Duration.ofMinutes(30)) } returns true

        conversationCacheService.saveChatMessage(conversationId, ownedMessage)

        verify { listOps.trim(key, -3L, -1L) }
    }

    @Test
    fun `saveChatMessageList saves every message under the same conversation key`() {
        val second = message.copy(id = 2L, content = "Hi again")
        val callback = slot<RedisCallback<Any?>>()
        every { redisTemplate.executePipelined(capture(callback)) } answers {
            callback.captured.doInRedis(mockk(relaxed = true))
            emptyList()
        }
        every { listOps.rightPush(key, any()) } returns 1L
        every { listOps.trim(key, -10L, -1L) } just Runs
        every { redisTemplate.expire(key, Duration.ofMinutes(30)) } returns true

        conversationCacheService.saveChatMessageList(conversationId, listOf(message, second))

        verify { listOps.rightPush(key, objectMapper.writeValueAsString(message)) }
        verify { listOps.rightPush(key, objectMapper.writeValueAsString(second)) }
    }

    companion object {
        private val FIXED_TIME = java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0)
    }
}
