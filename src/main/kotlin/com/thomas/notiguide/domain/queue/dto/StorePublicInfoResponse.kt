package com.thomas.notiguide.domain.queue.dto

import java.util.UUID

data class StorePublicInfoResponse(
    val id: UUID,
    val name: String,
    val address: String?,
    val isActive: Boolean,
    val queueState: String = "ACTIVE",
    val maxQueueSize: Int = 0
)
