package org.timpeng.chatbot.exception

class ConversationNotFoundException: RuntimeException {
    constructor(message: String) : super(message)
}