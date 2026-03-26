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
    val position: Long?
)

data class IssueTicketResponse(
    val storeId: UUID,
    val ticket: TicketDto
)

data class TicketStatusResponse(
    val status: TicketStatus,
    val positionInQueue: Long?,
    val estimatedWaitTime: Long? = null
)

data class NextTicketResponse(
    val ticket: TicketDto?
)

data class QueueSizeResponse(
    val queueSize: Long
)
