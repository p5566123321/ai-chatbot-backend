package org.timpeng.chatbot.queue

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `app.queue.*` settings shared by every [RedisStreamConsumer] this app wires up. Grouped into
 * one injectable holder — rather than repeating three `@Value` annotations at each call site —
 * since these three knobs (retry ceiling, poll block time, reclaim idle threshold) apply
 * uniformly across job types (chat today, embeddings per Phase 4 later); a new job type reuses
 * this bean instead of copying its own defaults. Values here mirror [RedisStreamConsumer]'s own
 * constructor defaults, which apply only when this bean isn't used (e.g. in tests that construct
 * a consumer directly).
 */
@Component
class QueueProperties(
    @Value($$"${app.queue.max-attempts}") val maxAttempts: Int = 3,
    @Value($$"${app.queue.block-timeout-ms}") val blockTimeoutMs: Long = 5000,
    @Value($$"${app.queue.reclaim-idle-ms}") val reclaimIdleMs: Long = 30000,
)
