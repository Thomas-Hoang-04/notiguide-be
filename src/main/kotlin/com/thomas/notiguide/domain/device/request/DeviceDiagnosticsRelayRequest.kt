package com.thomas.notiguide.domain.device.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class DeviceDiagnosticsRelayRequest(
    val publicId: String,
    @field:Min(0) @field:Max(100)
    val freeHeapPct: Int,
    @field:Min(-127) @field:Max(0)
    val rssi: Int?,
    @field:Min(0)
    val uptimeMs: Long,
    @field:Min(0)
    val dispatchDaily: Int,
    @field:Min(0)
    val dispatchTotal: Int,
    val wifiConnected: Boolean,
    val ip: String?,
    val firmwareVersion: String?
)
