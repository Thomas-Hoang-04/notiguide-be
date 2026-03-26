package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class UpdateStoreSettingsRequest(
    @field:Min(0) val maxQueueSize: Int? = null,
    @field:Min(0) @field:Max(600) val gracePeriodSec: Int? = null,
    val noShowAction: String? = null,
    @field:Min(0) @field:Max(5) val maxRequeues: Int? = null,
    @field:Min(1) @field:Max(20) val requeueOffset: Int? = null,
    @field:Min(1) @field:Max(10) val alertThreshold: Int? = null
)
