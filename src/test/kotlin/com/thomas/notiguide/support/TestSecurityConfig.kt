package com.thomas.notiguide.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@TestConfiguration
@EnableWebFluxSecurity
class TestSecurityConfig {
    @Bean
    fun testSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain = http
        .csrf { it.disable() }
        .authorizeExchange { it.anyExchange().permitAll() }
        .build()
}
