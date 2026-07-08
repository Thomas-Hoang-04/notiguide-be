package com.thomas.notiguide.domain.device.request

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

data class RosterRelayRequest(
    @field:Min(0)
    val seq: Int = 0,
    @field:Valid
    @field:Size(max = 32)
    val receivers: List<RosterRelayReceiver> = emptyList()
)

