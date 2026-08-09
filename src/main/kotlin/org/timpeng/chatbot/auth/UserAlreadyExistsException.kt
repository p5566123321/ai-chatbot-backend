package org.timpeng.chatbot.auth

class UserAlreadyExistsException : RuntimeException {
    constructor(message: String) : super(message)
}
