package com.thomas.notiguide.domain.analytics.response

data class StoreSummaryResponse(
    val period: String,
    val totalIssued: Long,
    val totalCompleted: Long,
    val totalCancelled: Long,
    val totalSkipped: Long,
    val avgWaitSeconds: Double?,
    val avgServiceSeconds: Double?,
    val medianWaitSeconds: Double?,
    val peakHour: Int?,
    val cancelRate: Double?,
    val skipRate: Double?
)