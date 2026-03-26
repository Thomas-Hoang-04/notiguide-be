package com.thomas.notiguide.core.jwt

import com.auth0.jwt.exceptions.JWTVerificationException
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.model.ErrorResponse
import com.thomas.notiguide.domain.admin.service.SessionService
import com.thomas.notiguide.shared.principal.AdminPrincipalAuthToken
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@Component
class JWTAuthFilter(
    private val appProperties: AppProperties,
    private val jwtManager: JWTManager,
    private val jwtToPrincipal: JWTToPrincipal,
    private val objectMapper: ObjectMapper,
    private val sessionService: SessionService
) : CoWebFilter() {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val skipPaths = setOf("/api/auth/login", "/api/auth/logout", "/api/auth/refresh")

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain
    ) {
        val path = exchange.request.path.value()
        if (path in skipPaths) return chain.filter(exchange)

        val token = exchange.extractToken() ?: return chain.filter(exchange)

        try {
            val decodedJwt = jwtManager.verify(token)

            val tokenHash = TokenHashUtil.sha256(token)
            if (sessionService.isRevoked(tokenHash)) {
                writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", "Session revoked")
                return
            }

            exchange.attributes["tokenHash"] = tokenHash
            sessionService.updateLastActive(tokenHash)

            val principal = jwtToPrincipal.convert(decodedJwt)
            val authToken = AdminPrincipalAuthToken(principal)
            val context = ReactiveSecurityContextHolder.withAuthentication(authToken)

            withContext(ReactorContext(context)) {
                chain.filter(exchange)
            }
        } catch (ex: JWTVerificationException) {
            log.warn("JWT verification failed: {} [{}]", ex.message, exchange.request.path)
            writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", ex.message ?: "JWT verification failed")
        } catch (ex: BadCredentialsException) {
            log.warn("Bad credentials: {} [{}]", ex.message, exchange.request.path)
            writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", ex.message ?: "Bad credentials")
        } catch (ex: HttpException) {
            log.warn("HTTP exception in JWT filter: {} [{}]", ex.message, exchange.request.path)
            writeErrorResponse(exchange, ex.status, ex.status.reasonPhrase, ex.message)
        } catch (ex: Exception) {
            log.error("Unexpected error during authentication [{}]", exchange.request.path, ex)
            writeErrorResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred")
        }
    }

    private suspend fun writeErrorResponse(
        exchange: ServerWebExchange,
        status: HttpStatus,
        error: String,
        message: String
    ) {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON

        val body = ErrorResponse(
            timestamp = LocalDateTime.now(),
            code = status.value(),
            error = error,
            message = message,
            path = exchange.request.path.toString(),
            method = exchange.request.method.name()
        )
        val bytes = objectMapper.writeValueAsBytes(body)
        val buffer = response.bufferFactory().wrap(bytes)
        response.writeWith(Mono.just(buffer)).awaitSingleOrNull()
    }

    private fun ServerWebExchange.extractToken(): String? {
        val authHeader = this.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader != null && authHeader.startsWith("Bearer "))
            return authHeader.substring(7)

        return this.request.cookies
            .getFirst(appProperties.cookie.name)
            ?.value
            ?.takeIf { it.isNotBlank() }
    }
}
