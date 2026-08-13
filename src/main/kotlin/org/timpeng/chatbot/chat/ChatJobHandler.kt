package org.timpeng.chatbot.chat

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.timpeng.chatbot.conversation.ConversationHistoryService
import org.timpeng.chatbot.conversation.ConversationService
import org.timpeng.chatbot.conversation.message.Role
import org.timpeng.chatbot.llm.LlmProvider
import org.timpeng.chatbot.exception.StreamCancelledException
import org.timpeng.chatbot.queue.Job
import org.timpeng.chatbot.queue.JobHandler
import org.timpeng.chatbot.redis.GeneratingStatusService

private const val PROGRESS_FLUSH_INTERVAL_MS = 400L

/**
 * Executes one [ChatJobPayload] job pulled off the chat queue by
 * [org.timpeng.chatbot.queue.RedisStreamConsumer] — this is the same work
 * `ChatService.streamChat`'s `CompletableFuture.runAsync` block used to do directly, just
 * relocated behind the queue (see docs/decision/006-queue-technology-selection.md).
 *
 * Unlike [JobHandler]'s general "throw to trigger retry/DLQ" contract, this handler never
 * rethrows: every failure mode (LLM error, disconnected client, missing emitter) is handled
 * terminally inside — sends an SSE error event and/or persists a partial response, then returns
 * normally. A queue-level retry would re-run `streamGenerate` from scratch against an emitter
 * that may have already sent chunks to the client and been closed, which would duplicate or
 * corrupt output rather than recover anything. Chat's failure handling was already
 * comprehensive before this used the queue; retry semantics matter more for future job types
 * that don't have that.
 */
@Component
class ChatJobHandler(
    private val conversationService: ConversationService,
    private val historyService: ConversationHistoryService,
    private val llmProvider: LlmProvider,
    private val generatingStatusService: GeneratingStatusService,
    private val emitterRegistry: SseEmitterRegistry,
) : JobHandler<ChatJobPayload> {

    private val logger = LoggerFactory.getLogger(ChatJobHandler::class.java)

    override fun handle(job: Job<ChatJobPayload>) {
        val conversationId = job.payload.conversationId
        val handle = emitterRegistry.get(conversationId)
        if (handle == null) {
            // The producer's controller thread/process is gone (crash/restart between enqueue
            // and delivery, or this is a redelivery long after the original request ended) — no
            // connection left to stream to. Not a job failure, just nothing left to do.
            logger.warn("No live SSE emitter registered for conversationId=$conversationId, dropping job")
            generatingStatusService.clearGenerating(conversationId)
            return
        }

        val emitter = handle.emitter
        val fullResponse = StringBuilder()
        var lastFlushAt = 0L
        try {
            val messages = historyService.getHistory(conversationId)
            llmProvider.streamGenerate(messages) { chunk ->
                if (handle.cancelled.get()) throw StreamCancelledException()
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
            // Also removed by the emitter's own onCompletion callback (ChatService.streamChat) —
            // harmless to repeat, and this covers the case where completion never fires for any
            // reason.
            emitterRegistry.remove(conversationId)
        }
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
