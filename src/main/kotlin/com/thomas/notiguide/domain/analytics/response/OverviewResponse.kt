package com.thomas.notiguide.domain.analytics.response

import com.thomas.notiguide.domain.analytics.model.StoreAnalyticsSummary

data class OverviewResponse(
    val period: String,
    val totalStores: Long,
    val totalIssued: Long,
    val totalCompleted: Long,
    val avgWaitSeconds: Double?,
    val stores: List<StoreAnalyticsSummary>
)