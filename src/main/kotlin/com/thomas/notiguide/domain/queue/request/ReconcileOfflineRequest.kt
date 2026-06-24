package com.thomas.notiguide.domain.queue.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class ReconcileOfflineRequest(
    @field:NotNull
    @field:Size(max = 500)
    val transitions: List<OfflineTransition> = emptyList()
)

data class OfflineTransition(
    @field:NotNull val ticketId: UUID,
    @field:NotNull val action: OfflineAction,
    @field:Size(max = 40) val at: String? = null
)

enum class OfflineAction { SERVE, CANCEL, NO_SHOW }
