package org.timpeng.chatbot.chat

import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.exception.ChatException
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.queue.JobQueue
import org.timpeng.chatbot.redis.GeneratingStatusService

@Service
class ChatService(
    private val conversationService: ConversationService,
    private val llmProvider: LlmProvider,
    private val generatingStatusService: GeneratingStatusService,
    private val emitterRegistry: SseEmitterRegistry,
    private val chatJobQueue: JobQueue<ChatJobPayload>,
) {

    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    @Timed(value = "total.chat.time", description = "聊天耗時", percentiles = [0.5, 0.95, 0.99])
    fun chat(conversationId: String, ownerId: Long, userMsg: String): ChatResponse {
        conversationService.requireOwnedConversation(conversationId, ownerId)

        val messages = conversationService.saveUserMessage(conversationId, userMsg)

        val llmResponse = runCatching {
            llmProvider.generate(messages)
        }.getOrElse { e ->
            logger.error("LLM API failed", e)
            throw ChatException("AI service unable to response.", e)
        }

        return conversationService.saveAssistantMessage(conversationId, llmResponse)
    }

    fun streamChat(conversationId: String, ownerId: Long, userMsg: String, emitter: SseEmitter) {
        // Runs before any async dispatch so an unknown/not-owned conversationId still surfaces
        // as a normal synchronous 404 via GlobalExceptionHandler, not inside the emitter.
        conversationService.requireOwnedConversation(conversationId, ownerId)
        conversationService.saveUserMessage(conversationId, userMsg)

        val handle = emitterRegistry.register(conversationId, emitter)
        emitter.onTimeout {
            logger.warn("SSE stream timed out for conversationId=$conversationId")
            handle.cancelled.set(true)
            runCatching { emitter.complete() }
        }
        emitter.onError { e ->
            logger.warn("SSE stream errored for conversationId=$conversationId: ${e.message}")
            handle.cancelled.set(true)
        }
        emitter.onCompletion {
            handle.cancelled.set(true)
            emitterRegistry.remove(conversationId)
        }

        // The Gemini call blocks on network I/O for the whole generation, so it's handed to the
        // chat job queue rather than run on the servlet thread: ChatJobHandler, driven by
        // RedisStreamConsumer's own daemon thread, does the actual streamGenerate call and
        // writes back to `emitter` via emitterRegistry (see docs/decision/006). The servlet
        // thread is freed as soon as enqueue() returns, and the onTimeout/onError callbacks
        // above are driven by the container's own async listener rather than only firing when a
        // write happens to fail.
        generatingStatusService.markGenerating(conversationId)
        chatJobQueue.enqueue(ChatJobPayload(conversationId))
    }

    fun streamStatus(conversationId: String, ownerId: Long): StreamStatusResponse {
        conversationService.requireOwnedConversation(conversationId, ownerId)
        val partial = generatingStatusService.getGeneratingProgress(conversationId)
        return if (partial != null) StreamStatusResponse(generating = true, partial = partial)
        else StreamStatusResponse(generating = false)
    }
}