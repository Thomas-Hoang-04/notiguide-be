package com.thomas.notiguide.core.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rate-limit")
data class RateLimitProperties(
    val strict: TierConfig = TierConfig(windowSeconds = 60, maxRequests = 20),
    val auth: TierConfig = TierConfig(windowSeconds = 60, maxRequests = 10),
    val standard: TierConfig = TierConfig(windowSeconds = 60, maxRequests = 60),
    val enabled: Boolean = true
) {
    data class TierConfig(
        val windowSeconds: Long = 60,
        val maxRequests: Long = 20
    )
}
