package com.thomas.notiguide.domain.device.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class ApproveDeviceRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val assignedName: String,

    @field:NotNull
    val storeId: UUID
)
