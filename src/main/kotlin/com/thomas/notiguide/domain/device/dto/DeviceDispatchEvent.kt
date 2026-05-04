package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.DeviceDispatchEventType
import com.thomas.notiguide.domain.device.types.DeviceDispatchStopDisposition
import java.util.UUID

data class DeviceDispatchEvent(
    val type: DeviceDispatchEventType,
    val storeId: UUID,
    val ticketId: UUID,
    val ticketNumber: String?,
    val deviceId: UUID,
    val disposition: DeviceDispatchStopDisposition? = null
)