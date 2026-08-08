package org.timpeng.chatbot.queue

import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * One dead-lettered job, as recorded by [RedisStreamConsumer.deadLetter]: the original job's
 * still-raw JSON payload (a `DlqReader` has no per-queue payload type, so deserializing it is
 * left to the caller) plus why and when it was dead-lettered. [attempt]/[failedAt] are nullable
 * because they're parsed from stream field strings and a malformed/older entry shouldn't make
 * the whole read fail - better to surface a partially-known entry than hide it.
 */
data class DlqEntry(
    val id: String,
    val payload: String,
    val reason: String?,
    val attempt: Int?,
    val failedAt: Instant?,
)

/**
 * Read-only access to a job queue's dead-letter stream (`{streamKey}:dlq`). Before this, the
 * only way to look was a manual `XRANGE {stream}:dlq - +` — see ADR-006's "Future considerations"
 * (DLQ inspection tooling). Kept separate from [JobQueue]/[JobHandler]: this is an ops/read-side
 * concern that call sites enqueueing or handling jobs never need.
 */
@Component
class DlqReader(private val redisTemplate: StringRedisTemplate) {

    private val ops = redisTemplate.opsForStream<String, String>()

    /** Most recently dead-lettered entries first, up to [limit]. */
    fun list(streamKey: String, limit: Int): List<DlqEntry> =
        ops.reverseRange(dlqKey(streamKey), Range.unbounded<String>(), Limit.limit().count(limit))
            .map { record ->
                val fields = record.value
                DlqEntry(
                    id = record.id.value,
                    payload = fields[PAYLOAD_FIELD].orEmpty(),
                    reason = fields[REASON_FIELD],
                    attempt = fields[ATTEMPT_FIELD]?.toIntOrNull(),
                    failedAt = fields[FAILED_AT_FIELD]?.let { runCatching { Instant.parse(it) }.getOrNull() },
                )
            }

    /** Total entries currently sitting in the DLQ - not just the page [list] returns. */
    fun count(streamKey: String): Long = ops.size(dlqKey(streamKey)) ?: 0L

    private fun dlqKey(streamKey: String) = "$streamKey:dlq"
}
