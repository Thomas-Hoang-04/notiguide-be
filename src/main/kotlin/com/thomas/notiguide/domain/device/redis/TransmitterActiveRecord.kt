package com.thomas.notiguide.domain.device.redis

import java.time.OffsetDateTime
import java.util.UUID

data class TransmitterActiveRecord(
    val deviceId: UUID = UUID(0L, 0L),
    val electedAt: OffsetDateTime = OffsetDateTime.MIN
)