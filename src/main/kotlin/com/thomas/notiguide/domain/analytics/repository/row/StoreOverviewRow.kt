package com.thomas.notiguide.domain.analytics.repository.row

import java.util.UUID

data class StoreOverviewRow(
    val storeId: UUID,
    val storeName: String,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long,
    val avgWaitSeconds: Double?
)