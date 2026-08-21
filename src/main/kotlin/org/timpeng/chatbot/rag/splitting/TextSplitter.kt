package org.timpeng.chatbot.rag.splitting

/**
 * Ingestion 端把一份文件拆成多個 chunk 的抽象——切法會直接影響 embedding 的品質（chunk 太大則語意
 * 混雜、topK 檢索命中率下降；太小則失去上下文），因此獨立成介面，跟 `EmbeddingProvider`/`LlmProvider`
 * 一樣可以之後換實作（例如語意切分、按 Markdown 標題切）而不動呼叫端。
 */
interface TextSplitter {
    fun split(text: String): List<String>
}