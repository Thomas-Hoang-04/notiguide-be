package com.thomas.notiguide.domain.queue.response

import com.thomas.notiguide.domain.queue.dto.TicketDto

data class NextTicketResponse(
    val ticket: TicketDto?
)