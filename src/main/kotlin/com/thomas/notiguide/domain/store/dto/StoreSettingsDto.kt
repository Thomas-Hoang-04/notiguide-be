package com.thomas.notiguide.domain.store.dto

import java.time.OffsetDateTime

data class StoreSettingsDto(
    val storeId: String,
    val maxQueueSize: Int,
    val gracePeriodSec: Int,
    val noShowAction: String,
    val maxRequeues: Int,
    val requeueOffset: Int,
    val alertThreshold: Int,
    val updatedAt: OffsetDateTime?
)
