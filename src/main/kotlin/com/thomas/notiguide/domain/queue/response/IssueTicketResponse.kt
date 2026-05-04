package com.thomas.notiguide.domain.queue.response

import com.thomas.notiguide.domain.queue.dto.TicketDto

data class IssueTicketResponse(
    val storeId: String,
    val ticket: TicketDto
)