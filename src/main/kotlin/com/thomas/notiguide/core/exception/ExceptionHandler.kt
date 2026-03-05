package com.thomas.notiguide.core.exception

import com.auth0.jwt.exceptions.JWTVerificationException
import com.thomas.notiguide.core.exception.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDateTime

@RestControllerAdvice
class ExceptionHandler {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private fun generateTemplate(
        ex: Exception,
        status: HttpStatus,
        path: String,
        method: String
    ): ErrorResponse = ErrorResponse(
        timestamp = LocalDateTime.now(),
        code = status.value(),
        error = ex.javaClass.simpleName,
        message = ex.message ?: status.reasonPhrase,
        path = path,
        method = method
    )

    @ExceptionHandler(HttpException::class)
    fun handleHttpException(ex: HttpException, req: ServerHttpRequest): ResponseEntity<ErrorResponse> {
        logger.warn("HTTP exception: ${ex.message} [${req.method} ${req.path}]")
        val body = generateTemplate(ex, ex.status, req.path.toString(), req.method.name())
        return ResponseEntity.status(ex.status).body(body)
    }

    @ExceptionHandler(BadCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleBadCredentials(ex: BadCredentialsException, req: ServerHttpRequest): ErrorResponse {
        logger.warn("Bad credentials: ${ex.message} [${req.method} ${req.path}]")
        return generateTemplate(ex, HttpStatus.UNAUTHORIZED, req.path.toString(), req.method.name())
    }

    @ExceptionHandler(JWTVerificationException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleJwtVerification(ex: JWTVerificationException, req: ServerHttpRequest): ErrorResponse {
        logger.warn("JWT verification failed: ${ex.message} [${req.method} ${req.path}]")
        return generateTemplate(ex, HttpStatus.UNAUTHORIZED, req.path.toString(), req.method.name())
    }

    @ExceptionHandler(AccessDeniedException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleAccessDenied(ex: AccessDeniedException, req: ServerHttpRequest): ErrorResponse {
        logger.warn("Access denied: ${ex.message} [${req.method} ${req.path}]")
        return generateTemplate(ex, HttpStatus.FORBIDDEN, req.path.toString(), req.method.name())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgument(ex: IllegalArgumentException, req: ServerHttpRequest): ErrorResponse {
        logger.warn("Illegal argument: ${ex.message} [${req.method} ${req.path}]")
        return generateTemplate(ex, HttpStatus.BAD_REQUEST, req.path.toString(), req.method.name())
    }

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleGeneric(ex: Exception, req: ServerHttpRequest): ErrorResponse {
        logger.error("Unhandled exception [${req.method} ${req.path}]", ex)
        return generateTemplate(ex, HttpStatus.INTERNAL_SERVER_ERROR, req.path.toString(), req.method.name())
    }
}
