package com.thomas.notiguide.core.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

@Configuration
class Argon2PwdEncoder {

    @Bean
    fun passwordEncoder(): Argon2PasswordEncoder
        = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
}