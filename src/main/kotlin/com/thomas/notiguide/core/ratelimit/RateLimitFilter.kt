package com.thomas.notiguide.core.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.exception.model.ErrorResponse
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.LocalDateTime

@Component
class RateLimitFilter(
    private val rateLimitProperties: RateLimitProperties,
    private val rateLimiter: RateLimiter,
    private val objectMapper: ObjectMapper
) : CoWebFilter() {

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain
    ) {
        if (exchange.request.method == HttpMethod.OPTIONS)
            return chain.filter(exchange)

        if (!rateLimitProperties.enabled)
            return chain.filter(exchange)

        val tier = resolveTier(exchange.request.path.value()) ?: return chain.filter(exchange)
        val clientIp = extractClientIp(exchange)
        val key = "ratelimit:${tier.key}:$clientIp"
        val result = rateLimiter.isAllowed(key, tier.config.windowSeconds, tier.config.maxRequests)

        if (result.allowed) {
            if (result.remaining >= 0)
                applyRateLimitHeaders(exchange.response.headers, tier.config.maxRequests, result)
            return chain.filter(exchange)
        }

        applyRateLimitHeaders(exchange.response.headers, tier.config.maxRequests, result)

        writeLimitExceededResponse(exchange, result)
    }

    private fun resolveTier(path: String): TierMatch? = when {
        path == "/api/queue/public" || path.startsWith("/api/queue/public/") ->
            TierMatch(key = "strict", config = rateLimitProperties.strict)
        path == "/api/auth" || path.startsWith("/api/auth/") ->
            TierMatch(key = "auth", config = rateLimitProperties.auth)
        path == "/api" || path.startsWith("/api/") ->
            TierMatch(key = "standard", config = rateLimitProperties.standard)
        else -> null
    }

    private fun extractClientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            val firstIp = forwarded.split(",").firstOrNull()?.trim()
            if (!firstIp.isNullOrBlank()) return normalizeIp(firstIp)
        }

        val remoteAddress = exchange.request.remoteAddress
        val ip = remoteAddress?.address?.hostAddress
            ?.takeIf { it.isNotBlank() }
            ?: remoteAddress?.hostString?.takeIf { it.isNotBlank() }
            ?: "unknown"
        return normalizeIp(ip)
    }

    private fun normalizeIp(ip: String): String =
        if (ip == "0:0:0:0:0:0:0:1" || ip == "::1") "127.0.0.1" else ip

    private fun applyRateLimitHeaders(
        headers: HttpHeaders,
        limit: Long,
        result: RateLimitResult
    ) {
        headers.set("X-RateLimit-Limit", limit.toString())
        headers.set("X-RateLimit-Remaining", result.remaining.toString())
        headers.set("X-RateLimit-Reset", result.resetAtEpochSeconds.toString())
    }

    private suspend fun writeLimitExceededResponse(
        exchange: ServerWebExchange,
        result: RateLimitResult
    ) {
        val response = exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON

        val retryInSeconds = (result.resetAtEpochSeconds - Instant.now().epochSecond).coerceAtLeast(1)
        val body = ErrorResponse(
            timestamp = LocalDateTime.now(),
            code = HttpStatus.TOO_MANY_REQUESTS.value(),
            error = HttpStatus.TOO_MANY_REQUESTS.reasonPhrase,
            message = "Rate limit exceeded. Try again in $retryInSeconds seconds.",
            path = exchange.request.path.toString(),
            method = exchange.request.method.name()
        )

        val bytes = objectMapper.writeValueAsBytes(body)
        val buffer = response.bufferFactory().wrap(bytes)
        response.writeWith(Mono.just(buffer)).awaitSingleOrNull()
    }

    private data class TierMatch(
        val key: String,
        val config: RateLimitProperties.TierConfig
    )
}
