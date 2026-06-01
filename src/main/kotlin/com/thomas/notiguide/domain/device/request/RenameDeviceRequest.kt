package com.thomas.notiguide.domain.device.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RenameDeviceRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val assignedName: String
)
