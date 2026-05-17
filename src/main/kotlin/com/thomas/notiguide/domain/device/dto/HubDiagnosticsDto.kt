package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.HubDiagnosticsSource
import java.time.OffsetDateTime

data class HubDiagnosticsDto(
    val freeHeapPct: Int,
    val rssi: Int?,
    val uptimeMs: Long,
    val dispatchDaily: Int,
    val dispatchTotal: Int,
    val wifiConnected: Boolean?,
    val ip: String?,
    val firmwareVersion: String?,
    val source: HubDiagnosticsSource,
    val updatedAt: OffsetDateTime
)

