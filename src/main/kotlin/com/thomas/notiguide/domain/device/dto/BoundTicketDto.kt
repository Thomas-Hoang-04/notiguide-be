package com.thomas.notiguide.domain.device.dto

import java.util.UUID

data class BoundTicketDto(
    val ticketId: UUID,
    val ticketNumber: String,
    val status: String
)
