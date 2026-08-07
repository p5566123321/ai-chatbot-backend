package org.timpeng.chatbot.queue

/**
 * A unit of work pulled off a queue, with delivery metadata layered on top of the caller's own
 * payload type. `attempt` starts at 1 and increments on redelivery — see [JobQueue]/[JobHandler]
 * for how this fits into the retry/DLQ story.
 */
data class Job<T>(
    val id: String,
    val payload: T,
    val attempt: Int,
)

/**
 * Backend-agnostic queue producer port. `id` is a plain opaque String rather than a
 * backend-shaped type (e.g. Redis Streams' "<ms>-<seq>") — every real broker can produce *some*
 * string identifier, so keeping it untyped here is what lets a future adapter swap (Kafka,
 * RabbitMQ, ...) stay behind this interface instead of leaking into call sites. See
 * docs/decision/006-queue-technology-selection.md.
 */
interface JobQueue<T> {
    fun enqueue(payload: T): String
}

/**
 * Business logic for processing one job. Throw to signal failure — the consumer (not the
 * handler) decides whether that means "retry" or "dead-letter", based on delivery count.
 */
fun interface JobHandler<T> {
    fun handle(job: Job<T>)
}
