package org.timpeng.chatbot.rag.embedding

interface EmbeddingProvider {
    suspend fun embed(text: String): FloatArray
}