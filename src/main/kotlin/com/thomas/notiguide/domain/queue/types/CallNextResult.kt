package com.thomas.notiguide.domain.queue.types

import com.thomas.notiguide.domain.queue.dto.TicketDto

sealed class CallNextResult {
    data class Success(val ticket: TicketDto) : CallNextResult()
    data object QueueEmpty : CallNextResult()
    data object GhostTicketSkipped : CallNextResult()
}
