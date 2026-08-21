package org.timpeng.chatbot.llm

import org.timpeng.chatbot.conversation.message.Message

interface LlmProvider {

    fun generate(
        messages: List<Message>,
        ownerId: Long
    ): LlmResponse

    fun streamGenerate(messagesWithUser: List<Message>, ownerId: Long, onChunk: (String) -> Unit)
}