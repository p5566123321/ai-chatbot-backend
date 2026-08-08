package org.timpeng.chatbot.chat

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.timpeng.chatbot.queue.DlqEntry
import org.timpeng.chatbot.queue.DlqReader

private const val MAX_LIMIT = 500

/**
 * Read-only inspection of the chat job queue's dead-letter stream — the "admin endpoint... once
 * there's an actual DLQ with real traffic worth watching" ADR-006 flagged as future work.
 * Deliberately GET-only: requeue/purge is a separate, bigger feature (reconstructing/validating a
 * payload to safely re-enqueue, deciding who's allowed to) that isn't built here.
 *
 * No auth — matches this app's posture everywhere else today (no Spring Security dependency
 * exists yet at all). Worth revisiting before this is ever reachable outside a trusted network.
 */
@RestController
@RequestMapping("/api/admin/queues/chat/dlq")
class ChatDlqController(private val dlqReader: DlqReader) {

    @GetMapping
    fun list(@RequestParam(defaultValue = "50") limit: Int): DlqListResponse =
        DlqListResponse(
            count = dlqReader.count(CHAT_STREAM_KEY),
            entries = dlqReader.list(CHAT_STREAM_KEY, limit.coerceIn(1, MAX_LIMIT)),
        )
}

data class DlqListResponse(val count: Long, val entries: List<DlqEntry>)
