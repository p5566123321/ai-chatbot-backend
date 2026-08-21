package org.timpeng.chatbot.rag.splitting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecursiveCharacterTextSplitterTest {

    private fun splitter(chunkSize: Int = 20, chunkOverlap: Int = 5) =
        RecursiveCharacterTextSplitter(chunkSize, chunkOverlap)

    @Test
    fun `blank input returns no chunks`() {
        assertEquals(emptyList(), splitter().split(""))
        assertEquals(emptyList(), splitter().split("   \n  "))
    }

    @Test
    fun `text shorter than chunkSize returns a single chunk`() {
        val text = "hello world"
        assertEquals(listOf(text), splitter(chunkSize = 100).split(text))
    }

    @Test
    fun `every chunk respects chunkSize`() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val chunks = splitter(chunkSize = 30, chunkOverlap = 5).split(text)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.length <= 30, "chunk exceeded chunkSize: '$it' (${it.length})") }
    }

    @Test
    fun `text that already fits is returned untouched, separators included`() {
        val text = "First paragraph.\n\nSecond paragraph."
        val chunks = splitter(chunkSize = 100, chunkOverlap = 10).split(text)

        assertEquals(listOf(text), chunks)
    }

    @Test
    fun `splitting prefers paragraph breaks over mid-sentence breaks`() {
        val text = "Paragraph one is short.\n\nParagraph two is also fairly short here."
        val chunks = splitter(chunkSize = 30, chunkOverlap = 0).split(text)

        // 兩段各自獨立成 chunk（在段落邊界切開），而不是忽略 \n\n 直接按空白切到句子中間。
        assertTrue(chunks.any { it.contains("Paragraph one") })
        assertTrue(chunks.any { it.contains("Paragraph two") })
        assertTrue(chunks.none { it.contains("one") && it.contains("two") })
    }

    @Test
    fun `long text with no separators falls back to a hard cut`() {
        val text = "a".repeat(45)
        val chunks = splitter(chunkSize = 20, chunkOverlap = 0).split(text)

        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 20) }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `adjacent chunks overlap by roughly chunkOverlap characters`() {
        val text = (1..20).joinToString(" ") { "token$it" }
        val chunks = splitter(chunkSize = 30, chunkOverlap = 10).split(text)

        for (i in 0 until chunks.size - 1) {
            val tailOfCurrent = chunks[i].takeLast(10)
            assertTrue(
                chunks[i + 1].startsWith(tailOfCurrent) || chunks[i + 1].contains(tailOfCurrent.trim()),
                "expected chunk ${i + 1} to carry overlap from chunk $i"
            )
        }
    }

    @Test
    fun `rejects non-positive chunkSize`() {
        assertFailsWith<IllegalArgumentException> { RecursiveCharacterTextSplitter(0, 0) }
    }

    @Test
    fun `rejects chunkOverlap greater than or equal to chunkSize`() {
        assertFailsWith<IllegalArgumentException> { RecursiveCharacterTextSplitter(10, 10) }
    }
}
