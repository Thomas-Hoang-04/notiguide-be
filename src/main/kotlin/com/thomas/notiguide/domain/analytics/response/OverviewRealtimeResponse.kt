package com.thomas.notiguide.domain.analytics.response

data class OverviewRealtimeResponse(
    val activeStores: Long,
    val totalQueueSize: Long,
    val totalServingCount: Long,
    val totalIssuedToday: Long,
    val estimatedAvgWaitMinutes: Double?
)