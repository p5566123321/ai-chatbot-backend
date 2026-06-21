package org.timpeng.chatbot.conversation.message

enum class Role(val geminiName: String) {
    USER("user"),
    ASSISTANT("model"),
    SYSTEM("system");
}