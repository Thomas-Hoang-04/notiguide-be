package com.thomas.notiguide.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val timezone: String = "Asia/Ho_Chi_Minh",
    val cors: CorsProperties = CorsProperties(),
    val cookie: CookieProperties = CookieProperties()
) {
    data class CorsProperties(
        val allowedOrigins: List<String> = listOf("http://localhost:3000")
    )

    data class CookieProperties(
        val name: String = "auth_token",
        val refreshName: String = "refresh_token",
        val secure: Boolean = false,
        val sameSite: String = "Strict",
        val domain: String? = null,
        val path: String = "/api",
        val refreshPath: String = "/api/auth"
    )
}

