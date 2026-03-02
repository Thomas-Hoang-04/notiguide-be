package com.thomas.notiguide.core.jwt

import org.springframework.http.HttpHeaders
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

class JWTAuthFilter(
    private val jwtManager: JWTManager,
    private val jwtToPrincipal: JWTToPrincipal
): CoWebFilter() {
    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain
    ) {
        val token = exchange.extractToken() ?: return chain.filter(exchange)

        val decodedJwt = jwtManager.verify(token)
        TODO("Not yet implemented")
    }

    private fun ServerWebExchange.extractToken(): String? {
        val authHeader = this.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        return if (authHeader != null && authHeader.startsWith("Bearer "))
            authHeader.substring(7)
        else null
    }
}