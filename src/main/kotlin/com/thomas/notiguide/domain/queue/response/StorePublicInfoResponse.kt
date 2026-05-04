package com.thomas.notiguide.domain.queue.response

data class StorePublicInfoResponse(
    val publicId: String,
    val name: String,
    val address: String?,
    val isActive: Boolean,
    val queueState: String = "ACTIVE",
    val maxQueueSize: Int = 0
)
