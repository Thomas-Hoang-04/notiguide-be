package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.DeviceLifecycleAckStatus
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceLifecycleCommandDto(
    val commandId: UUID,
    val action: String,
    val ackStatus: DeviceLifecycleAckStatus,
    val issuedAt: OffsetDateTime
)
