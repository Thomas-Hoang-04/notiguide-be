package com.thomas.notiguide.domain.queue.response

import com.thomas.notiguide.domain.queue.types.TicketStatus

data class TicketStatusResponse(
    val status: TicketStatus,
    val positionInQueue: Long?,
    val estimatedWaitTime: Long? = null
)