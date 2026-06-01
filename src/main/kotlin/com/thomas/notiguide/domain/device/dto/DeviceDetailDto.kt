package com.thomas.notiguide.domain.device.dto

import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceDetailDto(
    val id: UUID,
    val publicId: String?,
    val kind: DeviceKind,
    val status: DeviceStatus,
    val assignedName: String?,
    val storeId: UUID?,
    val storeName: String?,
    val firmwareVersion: String?,
    val lastSeenAt: OffsetDateTime?,
    val activatedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val hubSlot: Short? = null,
    val rfCode: DeviceRfCodeSummaryDto? = null,
    val lifecycleCommand: DeviceLifecycleCommandDto? = null,
    val isElected: Boolean? = null,
    val boundTicket: BoundTicketDto? = null,
    val diagnostics: HubDiagnosticsDto? = null
)
