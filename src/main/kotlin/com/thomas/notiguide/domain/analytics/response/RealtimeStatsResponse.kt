package com.thomas.notiguide.domain.analytics.response

data class RealtimeStatsResponse(
    val currentQueueSize: Long,
    val currentServingCount: Long,
    val ticketsIssuedToday: Long,
    val estimatedAvgWaitMinutes: Double?
)