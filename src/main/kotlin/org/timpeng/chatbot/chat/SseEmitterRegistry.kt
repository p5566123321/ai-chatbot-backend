package org.timpeng.chatbot.chat

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process conversationId -> live SSE connection lookup. [ChatService.streamChat] registers
 * the emitter the controller thread created; [ChatJobHandler], running on
 * [org.timpeng.chatbot.queue.RedisStreamConsumer]'s own daemon thread, looks it up by the
 * conversationId carried in [ChatJobPayload] — the emitter itself can't be serialized into the
 * job, so this map is how the two sides reconnect. [Handle.cancelled] replaces what used to be a
 * local `AtomicBoolean` inside `ChatService.streamChat`'s async block: since producer
 * (registers/flips it from SSE lifecycle callbacks) and consumer (reads it mid-stream) are now
 * different call stacks, the flag has to live somewhere both can reach.
 *
 * A plain `ConcurrentHashMap` only works because the producer and consumer are always the same
 * JVM — true today (single instance, see docs/decision/006-queue-technology-selection.md's
 * "known limitations"). If this app is horizontally scaled, replace this with Redis Pub/Sub:
 * each node subscribes to a channel per conversationId it's holding a connection for, and
 * [ChatJobHandler] publishes chunks to that channel instead of writing straight into a local
 * emitter — only the node that actually owns the connection would be subscribed.
 */
@Component
class SseEmitterRegistry {

    data class Handle(val emitter: SseEmitter, val cancelled: AtomicBoolean = AtomicBoolean(false))

    private val handles = ConcurrentHashMap<String, Handle>()

    fun register(conversationId: String, emitter: SseEmitter): Handle {
        val handle = Handle(emitter)
        handles[conversationId] = handle
        return handle
    }

    fun get(conversationId: String): Handle? = handles[conversationId]

    fun remove(conversationId: String) {
        handles.remove(conversationId)
    }
}
