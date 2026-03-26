package com.thomas.notiguide.domain.analytics.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.domain.analytics.entity.AnalyticsEvent
import com.thomas.notiguide.domain.analytics.entity.AnalyticsEventType
import com.thomas.notiguide.domain.analytics.repository.AnalyticsEventRepository
import com.thomas.notiguide.domain.queue.types.TicketStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class AnalyticsEventService(
    private val repository: AnalyticsEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun recordTicketIssued(storeId: UUID, ticketId: UUID, ticketNumber: String) {
        val metadata = objectMapper.writeValueAsString(mapOf("ticket_number" to ticketNumber))
        val event = AnalyticsEvent(
            time = OffsetDateTime.now(ZoneOffset.UTC),
            storeId = storeId,
            eventType = AnalyticsEventType.TICKET_ISSUED,
            ticketId = ticketId,
            waitDurationSeconds = null,
            deviceId = null,
            metadata = metadata
        )
        repository.insert(event)
        log.debug("Analytics: TICKET_ISSUED store={} ticket={}", storeId, ticketId)
    }

    suspend fun recordTicketCalled(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String,
        counterId: String?,
        issuedAt: Instant
    ) {
        val now = Instant.now()
        val waitSeconds = ((now.toEpochMilli() - issuedAt.toEpochMilli()) / 1000).toInt()

        val metadataMap = mutableMapOf("ticket_number" to ticketNumber)
        if (counterId != null) metadataMap["counter_id"] = counterId
        val metadata = objectMapper.writeValueAsString(metadataMap)

        val event = AnalyticsEvent(
            time = OffsetDateTime.now(ZoneOffset.UTC),
            storeId = storeId,
            eventType = AnalyticsEventType.TICKET_CALLED,
            ticketId = ticketId,
            waitDurationSeconds = waitSeconds,
            deviceId = null,
            metadata = metadata
        )
        repository.insert(event)
        log.debug("Analytics: TICKET_CALLED store={} ticket={} wait={}s", storeId, ticketId, waitSeconds)
    }

    suspend fun recordTicketCompleted(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String,
        calledAt: Instant?
    ) {
        val serviceSeconds = if (calledAt != null) {
            ((Instant.now().toEpochMilli() - calledAt.toEpochMilli()) / 1000).toInt()
        } else null

        val metadataMap = mutableMapOf("ticket_number" to ticketNumber)
        val metadata = objectMapper.writeValueAsString(metadataMap)

        val event = AnalyticsEvent(
            time = OffsetDateTime.now(ZoneOffset.UTC),
            storeId = storeId,
            eventType = AnalyticsEventType.TICKET_COMPLETED,
            ticketId = ticketId,
            waitDurationSeconds = serviceSeconds,
            deviceId = null,
            metadata = metadata
        )
        repository.insert(event)
        log.debug("Analytics: TICKET_COMPLETED store={} ticket={} service={}s", storeId, ticketId, serviceSeconds)
    }

    suspend fun recordTicketCancelled(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String,
        issuedAt: Instant?,
        previousStatus: TicketStatus
    ) {
        val durationSeconds = if (issuedAt != null) {
            ((Instant.now().toEpochMilli() - issuedAt.toEpochMilli()) / 1000).toInt()
        } else null

        val metadataMap = mutableMapOf(
            "ticket_number" to ticketNumber,
            "previous_status" to previousStatus.name
        )
        val metadata = objectMapper.writeValueAsString(metadataMap)

        val event = AnalyticsEvent(
            time = OffsetDateTime.now(ZoneOffset.UTC),
            storeId = storeId,
            eventType = AnalyticsEventType.TICKET_CANCELLED,
            ticketId = ticketId,
            waitDurationSeconds = durationSeconds,
            deviceId = null,
            metadata = metadata
        )
        repository.insert(event)
        log.debug("Analytics: TICKET_CANCELLED store={} ticket={} previous={}", storeId, ticketId, previousStatus)
    }

    suspend fun recordTicketSkipped(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String,
        issuedAt: Instant?
    ) {
        val durationSeconds = if (issuedAt != null) {
            ((Instant.now().toEpochMilli() - issuedAt.toEpochMilli()) / 1000).toInt()
        } else null

        val metadataMap = mutableMapOf("ticket_number" to ticketNumber)
        val metadata = objectMapper.writeValueAsString(metadataMap)

        val event = AnalyticsEvent(
            time = OffsetDateTime.now(ZoneOffset.UTC),
            storeId = storeId,
            eventType = AnalyticsEventType.TICKET_SKIPPED,
            ticketId = ticketId,
            waitDurationSeconds = durationSeconds,
            deviceId = null,
            metadata = metadata
        )
        repository.insert(event)
        log.debug("Analytics: TICKET_SKIPPED store={} ticket={}", storeId, ticketId)
    }
}
