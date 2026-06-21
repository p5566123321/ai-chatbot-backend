package org.timpeng.chatbot.conversation

class ConversationNotFoundException: RuntimeException {
    constructor(message: String) : super(message)
}