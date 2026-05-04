package com.thomas.notiguide.domain.device.redis

import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceActivationRecord(
    val deviceId: UUID = UUID(0L, 0L),
    val publicKeyFingerprint: String = "",
    val registrationNonce: String = "",
    val nonce: String? = null,
    val issuedAt: OffsetDateTime = OffsetDateTime.MIN,
    val expiresAt: OffsetDateTime = OffsetDateTime.MIN,
    val status: DeviceActivationStatus = DeviceActivationStatus.PENDING
)