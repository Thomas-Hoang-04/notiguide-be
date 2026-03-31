package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

data class CreateStoreRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 1000) val address: String? = null,
    val allowJumpCall: Boolean = false,
    val allowNoShow: Boolean = false,
    @field:Min(0) val maxQueueSize: Int = 0,
    @field:Min(0) @field:Max(600) val gracePeriodSec: Int = 0,
    val noShowAction: String = "SKIP",
    @field:Min(1) @field:Max(5) val maxRequeues: Int = 1,
    @field:Min(1) @field:Max(20) val requeueOffset: Int = 3,
    @field:Min(1) @field:Max(10) val alertThreshold: Int = 2
)
