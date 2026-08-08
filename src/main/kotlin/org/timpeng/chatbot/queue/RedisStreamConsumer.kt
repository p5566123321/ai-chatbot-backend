package org.timpeng.chatbot.queue

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.data.domain.Range
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

// internal (not private) so DlqReader can read the same field names deadLetter() writes with,
// rather than duplicating these string literals and risking the two sides drifting apart.
internal const val REASON_FIELD = "reason"
internal const val ATTEMPT_FIELD = "attempt"
internal const val FAILED_AT_FIELD = "failedAt"
private const val RECLAIM_BATCH_SIZE = 10L
private const val LOOP_ERROR_BACKOFF_MS = 1000L
private const val READ_BATCH_SIZE = 10L
private const val MAX_RECLAIM_BACKOFF_MS = 600_000L // 10 min safety cap regardless of attempt count

/**
 * Idle threshold a pending entry must sit past before it's eligible for reclaim, doubling per
 * prior delivery instead of a fixed [baseMs] every time — see ADR-006's "Future considerations"
 * (exponential backoff on retry). [deliveryCount] is Redis's own per-message delivery count (1
 * on first delivery), so the very first window is exactly [baseMs], unchanged from before this
 * existed; it only grows on redelivery. Top-level and `internal` (rather than a private method
 * on [RedisStreamConsumer]) purely so it's unit-testable as a pure function without standing up
 * a consumer against real Redis.
 */
internal fun reclaimBackoffMs(baseMs: Long, deliveryCount: Long): Long {
    val exponent = (deliveryCount - 1).coerceIn(0, 30)
    val multiplier = 1L shl exponent.toInt()
    return (baseMs * multiplier).coerceAtMost(MAX_RECLAIM_BACKOFF_MS)
}

/**
 * Redis Streams consumer-group loop for a single [JobHandler]. Owns a daemon thread: reclaims
 * timed-out pending entries first (so a crashed consumer's in-flight jobs aren't lost forever),
 * then blocks on XREADGROUP for new work. Retries up to [maxAttempts] using Redis's own
 * per-message delivery count (no hand-rolled attempt counter in the payload); jobs that exhaust
 * retries are moved to a companion "{streamKey}:dlq" stream instead of being silently dropped.
 * The idle threshold before a retry becomes reclaim-eligible doubles per prior delivery (see
 * [reclaimBackoffMs]) rather than staying fixed at [reclaimIdleMs] every time.
 *
 * See docs/decision/006-queue-technology-selection.md for why this shape (per-message ack,
 * consumer groups) was chosen — it minimizes, but doesn't eliminate, the rework a future swap to
 * a real broker (e.g. Kafka's offset-commit model has no exact equivalent of "ack one message
 * out of order") would need.
 */
class RedisStreamConsumer<T : Any>(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val streamKey: String,
    private val groupName: String,
    private val consumerName: String,
    private val payloadType: Class<T>,
    private val handler: JobHandler<T>,
    private val maxAttempts: Int = 3,
    private val blockTimeoutMs: Long = 5000,
    private val reclaimIdleMs: Long = 30000,
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(RedisStreamConsumer::class.java)
    private val dlqStreamKey = "$streamKey:dlq"
    private val ops: StreamOperations<String, String, String> = redisTemplate.opsForStream()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Creates the consumer group (idempotent) and starts the polling thread. */
    fun start() {
        ensureGroup()
        running = true
        thread = Thread(Runnable { runLoop() }, "queue-$streamKey-$consumerName").apply {
            isDaemon = true
            start()
        }
    }

    /** Spring calls this on shutdown; also safe to call directly (e.g. from tests). */
    override fun destroy() {
        running = false
        thread?.join(blockTimeoutMs + 1000)
    }

    private fun ensureGroup() {
        try {
            ops.createGroup(streamKey, ReadOffset.from("0"), groupName)
        } catch (e: RedisSystemException) {
            // BUSYGROUP = the group already exists, which is the expected steady-state case
            // (this runs on every app startup); anything else is a real failure.
            if (e.cause?.message?.contains("BUSYGROUP") != true) throw e
        }
    }

    private fun runLoop() {
        while (running) {
            try {
                reclaimStuckEntries()
                readAndProcessNew()
            } catch (e: Exception) {
                logger.error("Queue consumer loop error for stream=$streamKey", e)
                // readAndProcessNew()'s own XREADGROUP call blocks for blockTimeoutMs and so
                // naturally throttles the loop on the happy path, but a failure in
                // reclaimStuckEntries() (e.g. Redis unreachable, or - observed in practice - a
                // shutdown race where the connection factory bean stops before this consumer's
                // own destroy() gets to set running=false) skips straight past that and would
                // otherwise spin as fast as the JVM can throw, burning CPU and flooding logs.
                runCatching { Thread.sleep(LOOP_ERROR_BACKOFF_MS) }
            }
        }
    }

    /** Re-claims entries some other (likely crashed) consumer never acked, and reprocesses them. */
    private fun reclaimStuckEntries() {
        val pending = ops.pending(streamKey, groupName, Range.unbounded<String>(), RECLAIM_BATCH_SIZE)
        // PendingMessages is a Spring Data Streamable, whose own filter/map default methods
        // shadow the Kotlin stdlib ones (returning Streamable, not List) — materialize to a
        // plain List first via the iterator so the rest of this is unambiguous Kotlin.
        val pendingList = pending.iterator().asSequence().toList()
        val stale = pendingList.filter {
            it.elapsedTimeSinceLastDelivery >= Duration.ofMillis(reclaimBackoffMs(reclaimIdleMs, it.totalDeliveryCount))
        }
        if (stale.isEmpty()) return

        val claimed = ops.claim(
            streamKey,
            groupName,
            consumerName,
            // reclaimIdleMs (not each id's own larger backoff window) is deliberate here: it's
            // the smallest possible window (first delivery), so it never rejects an id the
            // filter above already vetted against its own (equal-or-larger) backoff — this is
            // just XCLAIM's own belt-and-suspenders idle check, not where backoff is enforced.
            XClaimOptions.minIdle(Duration.ofMillis(reclaimIdleMs)).ids(stale.map { it.idAsString }),
        )
        // `totalDeliveryCount` here is the count *before* the XCLAIM call below; XCLAIM itself
        // increments Redis's delivery counter by one (default behavior, no JUSTID), so the
        // attempt we're about to make is that count + 1 — using the pre-claim value directly
        // would under-count by one and let a job survive maxAttempts+1 real deliveries.
        val attemptById = stale.associate { it.idAsString to (it.totalDeliveryCount.toInt() + 1) }
        claimed.forEach { record -> process(record, attemptById[record.id.value] ?: 1) }
    }

    private fun readAndProcessNew() {
        val records = ops.read(
            Consumer.from(groupName, consumerName),
            StreamReadOptions.empty().count(READ_BATCH_SIZE).block(Duration.ofMillis(blockTimeoutMs)),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
        ) ?: emptyList()
        records.forEach { record -> process(record, attempt = 1) }
    }

    private fun process(record: MapRecord<String, String, String>, attempt: Int) {
        val sample = Timer.start(meterRegistry)
        val json = record.value[PAYLOAD_FIELD]
        val outcome = if (json == null) {
            logger.error("Queue record ${record.id} on stream=$streamKey has no payload field, acking to drop it")
            ops.acknowledge(streamKey, groupName, record.id)
            "malformed"
        } else {
            runCatching { handler.handle(Job(record.id.value, objectMapper.readValue(json, payloadType), attempt)) }
                .fold(
                    onSuccess = {
                        ops.acknowledge(streamKey, groupName, record.id)
                        "success"
                    },
                    onFailure = { e ->
                        if (attempt >= maxAttempts) {
                            deadLetter(json, e, attempt)
                            ops.acknowledge(streamKey, groupName, record.id)
                            "dead_lettered"
                        } else {
                            logger.warn(
                                "Job ${record.id} on stream=$streamKey failed (attempt $attempt/$maxAttempts)," +
                                    " will retry",
                                e,
                            )
                            "retry"
                        }
                    },
                )
        }
        sample.stop(
            Timer.builder("queue.job.process.time")
                .description("Queue job 處理耗時")
                .tag("stream", streamKey)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
        )
    }

    private fun deadLetter(json: String, error: Throwable, attempt: Int) {
        logger.error("Job on stream=$streamKey exhausted $attempt attempts, moving to $dlqStreamKey", error)
        ops.add(
            StreamRecords.string(
                mapOf(
                    PAYLOAD_FIELD to json,
                    REASON_FIELD to (error.message ?: error::class.simpleName ?: "unknown"),
                    ATTEMPT_FIELD to attempt.toString(),
                    FAILED_AT_FIELD to Instant.now().toString(),
                )
            ).withStreamKey(dlqStreamKey)
        )
    }
}
