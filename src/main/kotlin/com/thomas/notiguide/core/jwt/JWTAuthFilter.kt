package com.thomas.notiguide.core.jwt

import com.auth0.jwt.exceptions.JWTVerificationException
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.exception.model.ErrorResponse
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
    private val jwtManager: JWTManager,
    private val jwtToPrincipal: JWTToPrincipal,
    private val objectMapper: ObjectMapper
) : CoWebFilter() {

    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain
    ) {
        val token = exchange.extractToken() ?: return chain.filter(exchange)

        try {
            val decodedJwt = jwtManager.verify(token)
            val principal = jwtToPrincipal.convert(decodedJwt)
                ?: throw BadCredentialsException("Invalid JWT token")
            val authToken = AdminPrincipalAuthToken(principal)
            val context = ReactiveSecurityContextHolder.withAuthentication(authToken)

            withContext(ReactorContext(context)) {
                chain.filter(exchange)
            }
        } catch (ex: JWTVerificationException) {
            log.warn("JWT verification failed: {} [{}]", ex.message, exchange.request.path)
            writeErrorResponse(exchange, ex.message ?: "JWT verification failed")
        } catch (ex: BadCredentialsException) {
            log.warn("Bad credentials: {} [{}]", ex.message, exchange.request.path)
            writeErrorResponse(exchange, ex.message ?: "Bad credentials")
        }
    }

    private suspend fun writeErrorResponse(exchange: ServerWebExchange, message: String) {
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.contentType = MediaType.APPLICATION_JSON

        val body = ErrorResponse(
            timestamp = LocalDateTime.now(),
            code = HttpStatus.UNAUTHORIZED.value(),
            error = "Unauthorized",
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
        return if (authHeader != null && authHeader.startsWith("Bearer "))
            authHeader.substring(7)
        else null
    }
}
