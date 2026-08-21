package org.timpeng.chatbot.rag.search

data class RetrievedChunk(
    val id: String,
    val content: String,
    val documentId: Long,
    val score: Double // 相似度分數，數字越大代表越相似
)