package org.timpeng.chatbot.global

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.timpeng.chatbot.chat.ChatController
import org.timpeng.chatbot.llm.LlmException
import org.timpeng.chatbot.conversation.ConversationNotFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // 404 - Not Found
    @ExceptionHandler(ConversationNotFoundException::class)
    fun handleConversationNotFound(ex: ConversationNotFoundException): ResponseEntity<ErrorResponse> {

        logger.warn("Conversation not found: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponse(
                    status = 404,
                    error = "Not Found",
                    message = ex.message ?: "Resource not found"
                )
            )
    }

    // 400 - Bad Request
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {

        logger.warn("IllegalArgumentException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    status = 400,
                    error = "Bad Request",
                    message = ex.message ?: "Invalid request"
                )
            )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {

        logger.warn("HttpMessageNotReadableException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    status = 400,
                    error = "Bad Request",
                    message = "Malformed or missing request body"
                )
            )
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ErrorResponse> {

        logger.warn("HttpMediaTypeNotSupportedException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(
                ErrorResponse(
                    status = 415,
                    error = "Unsupported Media Type",
                    message = ex.message ?: "Content-Type not supported"
                )
            )
    }

    // 500 - Internal Server Error (catch-all)
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {

        logger.warn("Exception: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    status = 500,
                    error = "Internal Server Error",
                    message = ex.message ?: "Unexpected error occurred"
                )
            )
    }


    @ExceptionHandler(LlmException::class)
    fun handleLlmException(ex: LlmException): ResponseEntity<ErrorResponse> {

        logger.warn("LlmException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                ErrorResponse(
                    status = 503,
                    error = "LLM_UNAVAILABLE",
                    message = ex.message ?: "Unexpected error occurred"
                )
            )
    }
}