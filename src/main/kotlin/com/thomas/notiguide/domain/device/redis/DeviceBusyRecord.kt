package com.thomas.notiguide.domain.device.redis

import java.time.OffsetDateTime
import java.util.UUID

data class DeviceBusyRecord(
    val storeId: UUID = UUID(0L, 0L),
    val ticketId: UUID = UUID(0L, 0L),
    val boundAt: OffsetDateTime = OffsetDateTime.MIN
)