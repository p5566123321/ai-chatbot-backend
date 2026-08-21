package org.timpeng.chatbot.rag.splitting

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 仿照 LangChain 的 `RecursiveCharacterTextSplitter` 演算法自行實作，而不引入 LangChain/langchain4j
 * 依賴——這個專案是純 Kotlin/Spring，切法本身不複雜，沒有必要為此多背一個第三方框架。
 *
 * 演算法：依序嘗試一組分隔符（段落 → 換行 → 句號 → 空白，見 [DEFAULT_SEPARATORS]），優先用「語意較大」
 * 的分隔符切；切出來的片段若仍超過 [chunkSize] 才往下一層更細的分隔符遞迴切，避免把句子/段落切得比
 * 必要更碎。所有分隔符都切不動時（例如超長無空白字串）以 [chunkSize] 硬切保底，確保一定會終止。
 *
 * 切完的片段再貪婪地組回接近 [chunkSize] 大小的 chunk，相鄰 chunk 之間保留 [chunkOverlap] 字元重疊，
 * 讓檢索時語意剛好落在切點附近也不會遺失上下文。
 */
@Component
class RecursiveCharacterTextSplitter(
    @Value("\${app.rag.splitter.chunk-size:800}") private val chunkSize: Int,
    @Value("\${app.rag.splitter.chunk-overlap:100}") private val chunkOverlap: Int
) : TextSplitter {

    init {
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        require(chunkOverlap in 0 until chunkSize) {
            "chunkOverlap must be within [0, chunkSize), was $chunkOverlap (chunkSize=$chunkSize)"
        }
    }

    override fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val pieces = splitRecursive(text, DEFAULT_SEPARATORS)
        return mergeWithOverlap(pieces)
    }

    private fun splitRecursive(text: String, remainingSeparators: List<String>): List<String> {
        if (text.length <= chunkSize) return listOf(text)

        val separatorIndex = remainingSeparators.indexOfFirst { text.contains(it) }
        if (separatorIndex == -1) {
            // 沒有分隔符可用了（例如一長串沒有空白的字串）——直接硬切保底，避免遞迴切不動。
            return text.chunked(chunkSize)
        }

        val separator = remainingSeparators[separatorIndex]
        val nextSeparators = remainingSeparators.drop(separatorIndex + 1)

        return text.split(separator)
            .filter { it.isNotBlank() }
            .flatMap { part ->
                if (part.length > chunkSize) splitRecursive(part, nextSeparators) else listOf(part)
            }
    }

    /** 把切完的小片段用貪婪法組回 ~chunkSize 大小的 chunk，並在相鄰 chunk 間保留 overlap。 */
    private fun mergeWithOverlap(pieces: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (piece in pieces) {
            val joiner = if (current.isEmpty()) "" else " "
            if (current.isNotEmpty() && current.length + joiner.length + piece.length > chunkSize) {
                chunks += current.toString().trim()
                current = StringBuilder(overlapTail(current.toString()))
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(piece)
        }
        if (current.isNotBlank()) chunks += current.toString().trim()

        return chunks
    }

    private fun overlapTail(text: String): String =
        if (chunkOverlap == 0 || text.length <= chunkOverlap) "" else text.takeLast(chunkOverlap)

    companion object {
        val DEFAULT_SEPARATORS = listOf("\n\n", "\n", "。", ". ", " ")
    }
}
