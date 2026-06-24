package com.thomas.notiguide.core.sse

import java.util.UUID

data class QueueSseEvent(
    val type: String,
    val storeId: UUID,
    val ticketId: UUID,
    val ticketNumber: String? = null,
    val counterId: String? = null,
    val reason: String? = null,
    val deviceId: UUID? = null,
    val dispatchAction: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)