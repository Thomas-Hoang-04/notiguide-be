package com.thomas.notiguide.domain.queue.dto

import com.thomas.notiguide.domain.queue.types.TicketStatus
import java.time.Instant
import java.util.UUID

data class TicketDto(
    val id: UUID,
    val number: String,
    val status: TicketStatus,
    val issuedAt: Instant?,
    val calledAt: Instant?,
    val position: Long?,
    val deviceId: UUID? = null,
    val deviceName: String? = null
)

