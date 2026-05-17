package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.HubWarningType
import java.util.UUID

data class HubWarningDto(
    val deviceId: UUID,
    val deviceName: String?,
    val type: HubWarningType,
    val value: String
)