package org.timpeng.chatbot.chat

import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.llm.StreamCancelledException
import org.timpeng.chatbot.redis.GeneratingStatusService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

private const val PROGRESS_FLUSH_INTERVAL_MS = 400L

@Service
class ChatService(
    private val conversationService: ConversationService,
    private val llmProvider: LlmProvider,
    private val generatingStatusService: GeneratingStatusService,
) {

    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    @Timed(value = "total.chat.time", description = "聊天耗時", percentiles = [0.5, 0.95, 0.99])
    fun chat(conversationId: String, userMsg: String): ChatResponse {

        val messages = conversationService.saveUserMessage(conversationId, userMsg)

        val llmResponse = runCatching {
            llmProvider.generate(messages)
        }.getOrElse { e ->
            logger.error("LLM API failed", e)
            throw ChatException("AI service unable to response.", e)
        }

        return conversationService.saveAssistantMessage(conversationId, llmResponse)
    }

    fun streamChat(conversationId: String, userMsg: String, emitter: SseEmitter) {
        // Runs before any async dispatch so an unknown conversationId still surfaces
        // as a normal synchronous 404 via GlobalExceptionHandler, not inside the emitter.
        val messages = conversationService.saveUserMessage(conversationId, userMsg)

        val cancelled = AtomicBoolean(false)
        emitter.onTimeout {
            logger.warn("SSE stream timed out for conversationId=$conversationId")
            cancelled.set(true)
            runCatching { emitter.complete() }
        }
        emitter.onError { e ->
            logger.warn("SSE stream errored for conversationId=$conversationId: ${e.message}")
            cancelled.set(true)
        }
        emitter.onCompletion {
            cancelled.set(true)
        }

        // The Gemini call blocks on network I/O for the whole generation, so it runs off the
        // request thread: the servlet thread is freed immediately after saveUserMessage, and the
        // onTimeout/onError callbacks above are driven by the container's own async listener
        // rather than only firing when a write happens to fail.
        generatingStatusService.markGenerating(conversationId)

        CompletableFuture.runAsync {
            val fullResponse = StringBuilder()
            var lastFlushAt = 0L
            try {
                llmProvider.streamGenerate(messages) { chunk ->
                    if (cancelled.get()) throw StreamCancelledException()
                    fullResponse.append(chunk)
                    emitter.send(SseEmitter.event().name("message").data(chunk))

                    // Written to Redis so a reconnecting/polling client can see progress even
                    // without an open SSE connection; throttled since Gemini can emit dozens of
                    // chunks per second and every write costs a round-trip to Redis.
                    val now = System.currentTimeMillis()
                    if (now - lastFlushAt >= PROGRESS_FLUSH_INTERVAL_MS) {
                        generatingStatusService.updateGeneratingProgress(conversationId, fullResponse.toString())
                        lastFlushAt = now
                    }
                }
                conversationService.saveMessage(conversationId, Role.ASSISTANT, fullResponse.toString())
                emitter.complete()
            } catch (e: StreamCancelledException) {
                logger.info("Stream cancelled (client disconnected or timed out) for conversationId=$conversationId")
                persistPartialResponse(conversationId, fullResponse)
                // emitter is already terminated by the container at this point; nothing to send.
            } catch (e: Exception) {
                logger.error("Stream generation failed for conversationId=$conversationId", e)
                persistPartialResponse(conversationId, fullResponse)

                // completeWithError(), once the response is already committed by prior chunks,
                // aborts the connection without a clean chunk terminator - bytes written right
                // before the abort (including the error event below) can arrive truncated.
                // complete() is a graceful close, so prefer it once the error event is out; only
                // fall back to completeWithError() if the client is already gone (send itself failed).
                val errorEventSent = runCatching {
                    emitter.send(SseEmitter.event().name("error").data(e.message ?: "stream failed"))
                }.isSuccess

                if (errorEventSent) {
                    emitter.complete()
                } else {
                    emitter.completeWithError(e)
                }
            } finally {
                generatingStatusService.clearGenerating(conversationId)
            }
        }
    }

    fun streamStatus(conversationId: String): StreamStatusResponse {
        val partial = generatingStatusService.getGeneratingProgress(conversationId)
        return if (partial != null) StreamStatusResponse(generating = true, partial = partial)
        else StreamStatusResponse(generating = false)
    }

    private fun persistPartialResponse(conversationId: String, fullResponse: StringBuilder) {
        if (fullResponse.isBlank()) return
        runCatching {
            conversationService.saveMessage(
                conversationId,
                Role.ASSISTANT,
                "$fullResponse\n\n[回覆中斷]"
            )
        }.onFailure { saveError ->
            logger.error("Failed to persist partial assistant message for conversationId=$conversationId", saveError)
        }
    }
}