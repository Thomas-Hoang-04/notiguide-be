package com.thomas.notiguide.core.exception

import com.thomas.notiguide.core.exception.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDateTime

@RestControllerAdvice
class ExceptionHandler {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private fun <T: Exception> generateTemplate(
        ex: T,
        status: HttpStatus,
        path: String,
        method: String
    ): ErrorResponse<T> = ErrorResponse(
        timestamp = LocalDateTime.now(),
        code = status.value(),
        error = ex.javaClass,
        message = ex.message ?: status.reasonPhrase,
        path = path,
        method = method
    )

    // TODO: Add specific exception handlers here, e.g. for NotFoundException, BadRequestException, etc.
}