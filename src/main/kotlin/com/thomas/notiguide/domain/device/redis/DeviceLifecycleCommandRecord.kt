package com.thomas.notiguide.domain.device.redis

import com.thomas.notiguide.domain.device.types.DeviceLifecycleAckStatus
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceLifecycleCommandRecord(
    val commandId: UUID = UUID(0L, 0L),
    val action: String = "",
    val issuedAt: OffsetDateTime = OffsetDateTime.MIN,
    val ackStatus: DeviceLifecycleAckStatus = DeviceLifecycleAckStatus.PENDING
)