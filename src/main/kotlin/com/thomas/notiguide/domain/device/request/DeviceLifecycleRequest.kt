package com.thomas.notiguide.domain.device.request

import jakarta.validation.constraints.NotBlank

data class DeviceLifecycleRequest(
    @field:NotBlank
    val action: String
)
