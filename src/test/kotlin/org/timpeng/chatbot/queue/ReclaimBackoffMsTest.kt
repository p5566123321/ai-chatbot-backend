package org.timpeng.chatbot.queue

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Pure-function coverage for RedisStreamConsumer's exponential reclaim backoff (ADR-006's
// "Future considerations" - exponential backoff on retry). Deliberately not exercised through
// RedisStreamConsumerIntegrationTest's real-Redis timing, which would make the doubling itself
// slow and awkward to assert precisely.
class ReclaimBackoffMsTest {

    @Test
    fun `first delivery uses the base idle threshold unchanged`() {
        assertEquals(30_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 1))
    }

    @Test
    fun `doubles per additional delivery`() {
        assertEquals(30_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 1))
        assertEquals(60_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 2))
        assertEquals(120_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 3))
        assertEquals(240_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 4))
    }

    @Test
    fun `is capped so a large delivery count can't produce an unbounded wait`() {
        val result = reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 1000)

        assertEquals(600_000L, result)
    }

    @Test
    fun `never goes below the base threshold even for a zero or negative delivery count`() {
        // Defensive: Redis delivery counts start at 1 and only grow, but this guards against a
        // caller passing something unexpected without the multiplier collapsing to 0.
        assertEquals(30_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = 0))
        assertEquals(30_000L, reclaimBackoffMs(baseMs = 30_000L, deliveryCount = -5))
    }
}
