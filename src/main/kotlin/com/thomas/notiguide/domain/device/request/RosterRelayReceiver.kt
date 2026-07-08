package com.thomas.notiguide.domain.device.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RosterRelayReceiver(
    @field:Min(1)
    val slot: Int = 0,
    @field:NotBlank
    @field:Size(max = 8)
    val band: String = "",
    @field:Size(max = 100)
    val label: String? = null
)