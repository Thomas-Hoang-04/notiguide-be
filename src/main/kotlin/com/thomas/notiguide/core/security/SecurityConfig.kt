package com.thomas.notiguide.core.security

import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val passwordEncoder: PasswordEncoder,
) {
    // TODO: Implement security configuration for NotiGuide application, including authentication and authorization mechanisms.
}