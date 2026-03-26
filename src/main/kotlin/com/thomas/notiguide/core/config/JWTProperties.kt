package com.thomas.notiguide.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jwt")
data class JWTProperties(
    val accessExpirySeconds: Long = 900,
    val refreshExpirySeconds: Long = 604800,
    val privateKey: String,
    val privateKeyPassword: String,
    val publicKey: String
)
