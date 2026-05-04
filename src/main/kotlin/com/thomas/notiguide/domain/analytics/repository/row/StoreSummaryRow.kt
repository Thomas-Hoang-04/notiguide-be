package com.thomas.notiguide.domain.analytics.repository.row

data class StoreSummaryRow(
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