package com.thomas.notiguide.domain.queue.request

import java.util.UUID

data class IssueDeviceTicketRequest(
    val deviceId: UUID,
    val serviceTypeId: UUID? = null,
    val allowSerialFallback: Boolean = false
)
