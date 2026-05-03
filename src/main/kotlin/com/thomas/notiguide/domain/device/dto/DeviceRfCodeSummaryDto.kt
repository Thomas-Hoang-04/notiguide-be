package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import java.time.OffsetDateTime

data class DeviceRfCodeSummaryDto(
    val bits: Int,
    val byteLen: Int,
    val version: Int,
    val ack: DeviceRfAckStatus,
    val issuedAt: OffsetDateTime,
    val ackAt: OffsetDateTime?
)
