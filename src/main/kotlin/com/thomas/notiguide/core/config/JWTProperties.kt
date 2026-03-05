package com.thomas.notiguide.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jwt")
data class JWTProperties(
    val expirySeconds: Long = 86400,
    val privateKey: String,
    val privateKeyPassword: String,
    val publicKey: String
)
