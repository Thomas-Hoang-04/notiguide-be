package com.thomas.notiguide.domain.analytics.model

import java.util.UUID

data class StoreAnalyticsSummary(
    val storeId: UUID,
    val storeName: String,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long,
    val avgWaitSeconds: Double?
)