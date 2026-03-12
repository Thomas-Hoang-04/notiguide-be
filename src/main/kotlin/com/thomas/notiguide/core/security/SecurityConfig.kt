package com.thomas.notiguide.core.security

import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.domain.admin.service.AdminAuthService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val passwordEncoder: PasswordEncoder,
    private val jwtAuthFilter: JWTAuthFilter,
    private val adminAuthService: AdminAuthService
) {

    @Bean
    fun reactiveAuthenticationManager(): ReactiveAuthenticationManager =
        UserDetailsRepositoryReactiveAuthenticationManager(adminAuthService).apply {
            setPasswordEncoder(passwordEncoder)
        }

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        authenticationManager: ReactiveAuthenticationManager
    ): SecurityWebFilterChain = http
        .cors { }
        .csrf { it.disable() }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .authenticationManager(authenticationManager)
        .authorizeExchange {
            it.pathMatchers(
                "/api/auth/**",
                "/api/queue/public/**",
                "/actuator/health",
                "/actuator/info"
            ).permitAll()
            it.anyExchange().authenticated()
        }
        .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .build()
}