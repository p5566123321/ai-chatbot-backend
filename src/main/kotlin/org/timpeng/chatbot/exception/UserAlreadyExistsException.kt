package org.timpeng.chatbot.exception

class UserAlreadyExistsException : RuntimeException {
    constructor(message: String) : super(message)
}