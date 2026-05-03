package com.thomas.notiguide.domain.device.request

import com.thomas.notiguide.domain.device.types.DeviceHardwareModel
import com.thomas.notiguide.domain.device.types.DeviceKind
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class PassiveDeviceRegistrationRequest(
    @field:NotNull
    val hardwareModel: DeviceHardwareModel,

    @field:NotNull
    val kind: DeviceKind,

    @field:NotBlank
    @field:Size(max = 100)
    val assignedName: String,

    @field:NotNull
    val storeId: UUID,

    @field:NotBlank
    val rfCodeHex: String,

    val rfCodeBits: Int
)
