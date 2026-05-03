package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.domain.device.types.DeviceLifecycleAckStatus
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

data class DeviceActivationByDeviceRecord(
    val challengeId: UUID = UUID(0L, 0L)
)

data class DeviceLifecycleCommandRecord(
    val commandId: UUID = UUID(0L, 0L),
    val action: String = "",
    val issuedAt: OffsetDateTime = OffsetDateTime.MIN,
    val ackStatus: DeviceLifecycleAckStatus = DeviceLifecycleAckStatus.PENDING
)

enum class DeviceActivationStatus {
    PENDING,
    ISSUED
}
