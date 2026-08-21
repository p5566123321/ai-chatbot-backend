package org.timpeng.chatbot.rag.splitting

class SplittingService(
    private val textSplitter: TextSplitter
) {
    fun split(text: String): List<String> {
        return textSplitter.split(text)
    }
}