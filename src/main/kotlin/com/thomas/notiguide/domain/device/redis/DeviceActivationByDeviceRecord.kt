package com.thomas.notiguide.domain.device.redis

import java.util.UUID

data class DeviceActivationByDeviceRecord(
    val challengeId: UUID = UUID(0L, 0L)
)