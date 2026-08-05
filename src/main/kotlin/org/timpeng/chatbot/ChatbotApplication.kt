package org.timpeng.chatbot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
@RestController
class ChatbotApplication

fun main(args: Array<String>) {
    runApplication<ChatbotApplication>(*args)
}
