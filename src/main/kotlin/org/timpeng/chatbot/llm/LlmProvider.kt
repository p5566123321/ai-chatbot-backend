package org.timpeng.chatbot.llm

import org.timpeng.chatbot.conversation.message.Message

interface LlmProvider {

    fun generate(
        messages: List<Message>
    ): LlmResponse

    fun streamGenerate(messagesWithUser: List<Message>, onChunk: (String) -> Unit)
}