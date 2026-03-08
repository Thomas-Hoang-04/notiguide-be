package com.thomas.notiguide.domain.queue.dto

import java.time.Instant
import java.util.UUID

data class TicketDto(
    val id: UUID,
    val number: String,
    val status: String,
    val issuedAt: Instant?,
    val calledAt: Instant?,
    val position: Long?
)

data class IssueTicketResponse(
    val storeId: UUID,
    val ticket: TicketDto
)

data class TicketStatusResponse(
    val status: String,
    val positionInQueue: Long?,
    // TODO: populate from analytics domain when average service duration data is available
    val estimatedWaitTime: Long? = null
)

data class NextTicketResponse(
    val ticket: TicketDto?
)
