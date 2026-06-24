package com.thomas.notiguide.domain.queue.response

import java.util.UUID

data class ReconcileOfflineResponse(val results: List<ReconcileItemResult>)
data class ReconcileItemResult(val ticketId: UUID, val result: String)
