package com.thomas.notiguide.core.ratelimit

data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Long,
    val resetAtEpochSeconds: Long
)