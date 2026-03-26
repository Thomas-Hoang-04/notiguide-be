package com.thomas.notiguide.domain.analytics.entity

import java.time.OffsetDateTime
import java.util.UUID

data class AnalyticsEvent(
    val time: OffsetDateTime,
    val storeId: UUID,
    val eventType: AnalyticsEventType,
    val ticketId: UUID?,
    val waitDurationSeconds: Int?,
    val deviceId: UUID?,
    val metadata: String?
)

enum class AnalyticsEventType {
    TICKET_ISSUED,
    TICKET_CALLED,
    TICKET_COMPLETED,
    TICKET_CANCELLED,
    TICKET_SKIPPED,
    DEVICE_TRIGGERED
}
