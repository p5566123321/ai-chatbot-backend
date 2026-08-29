package org.timpeng.chatbot.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

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

    // 404 - Not Found
    @ExceptionHandler(DocumentNotFoundException::class)
    fun handleDocumentNotFound(ex: DocumentNotFoundException): ResponseEntity<ErrorResponse> {

        logger.warn("Document not found: {}", ex.message)

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

    // 400 - Bad Request (@Valid @RequestBody failures, e.g. RegisterRequest/LoginRequest)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {

        val message = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifEmpty { "Invalid request" }

        logger.warn("MethodArgumentNotValidException: {}", message)

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    status = 400,
                    error = "Bad Request",
                    message = message
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

    // 413 - Payload Too Large (DocumentController upload/replace exceeding
    // spring.servlet.multipart.max-file-size/max-request-size)
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {

        logger.warn("MaxUploadSizeExceededException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(
                ErrorResponse(
                    status = 413,
                    error = "Payload Too Large",
                    message = "Uploaded file exceeds the maximum allowed size"
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

    // 400 - Bad Request (no BYOK key set and no system-wide fallback configured — see
    // GenAIConfig/GeminiClientFactory)
    @ExceptionHandler(MissingApiKeyException::class)
    fun handleMissingApiKey(ex: MissingApiKeyException): ResponseEntity<ErrorResponse> {

        logger.warn("MissingApiKeyException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    status = 400,
                    error = "MISSING_API_KEY",
                    message = ex.message ?: "No Gemini API key configured"
                )
            )
    }

    // 503 - Service Unavailable (no system-wide embedding key configured — RAG has no per-user
    // BYOK path, unlike chat, so this is a deployment-wide condition, not something this caller
    // can fix themselves)
    @ExceptionHandler(RagUnavailableException::class)
    fun handleRagUnavailable(ex: RagUnavailableException): ResponseEntity<ErrorResponse> {

        logger.warn("RagUnavailableException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                ErrorResponse(
                    status = 503,
                    error = "RAG_UNAVAILABLE",
                    message = ex.message ?: "RAG is not configured on this deployment"
                )
            )
    }

    // 409 - Conflict (ADR-007)
    @ExceptionHandler(UserAlreadyExistsException::class)
    fun handleUserAlreadyExists(ex: UserAlreadyExistsException): ResponseEntity<ErrorResponse> {

        logger.warn("UserAlreadyExistsException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    status = 409,
                    error = "Conflict",
                    message = ex.message ?: "User already exists"
                )
            )
    }

    // 401 - Unauthorized (ADR-007; token-missing/invalid 401s are handled by SecurityConfig's
    // AuthenticationEntryPoint instead — this one is specifically for a wrong email/password on
    // POST /api/auth/login)
    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException): ResponseEntity<ErrorResponse> {

        logger.warn("BadCredentialsException: {}", ex.message)

        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(
                ErrorResponse(
                    status = 401,
                    error = "Unauthorized",
                    message = ex.message ?: "Invalid credentials"
                )
            )
    }
}