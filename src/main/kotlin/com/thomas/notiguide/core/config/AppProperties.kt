package com.thomas.notiguide.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val timezone: String = "Asia/Ho_Chi_Minh",
    val cors: CorsProperties = CorsProperties()
) {
    data class CorsProperties(
        val allowedOrigins: List<String> = listOf("http://localhost:3000")
    )
}


