package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.sse.QueueEventBroadcaster
import com.thomas.notiguide.core.sse.QueueSseEvent
import com.thomas.notiguide.domain.device.dto.DispatchTrackingRecord
import com.thomas.notiguide.domain.device.redis.DeviceBusyRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DispatchReconciliationServiceTest {

    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val valueOps = mockk<ReactiveValueOperations<String, String>>()
    private val broadcaster = mockk<QueueEventBroadcaster>(relaxed = true)

    // findAndRegisterModules() pulls in JavaTimeModule so DeviceBusyRecord's OffsetDateTime round-trips,
    // matching the Spring-configured ObjectMapper the service receives in production.
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val service = DispatchReconciliationService(redis, objectMapper, broadcaster)

    private val dispatchId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val deviceId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val storeId = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val ticketId = UUID.fromString("66666666-6666-6666-6666-666666666666")

    private val trackingKey = RedisKeyManager.dispatchTracking(dispatchId)
    private val pendingAckKey = RedisKeyManager.dispatchPendingAck(dispatchId)
    private val busyKey = RedisKeyManager.deviceBusy(deviceId)

    private fun trackingJson(): String = objectMapper.writeValueAsString(
        DispatchTrackingRecord(
            deviceId = deviceId,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = "A001"
        )
    )

    private fun busyJson(ticket: UUID): String = objectMapper.writeValueAsString(
        DeviceBusyRecord(
            storeId = storeId,
            ticketId = ticket,
            boundAt = OffsetDateTime.of(2026, 6, 10, 10, 0, 0, 0, ZoneOffset.UTC)
        )
    )

    @Test
    fun `failDispatch claims, releases the matching busy lock, and emits DEVICE_DISPATCH_FAILED`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.just(trackingJson())
        every { redis.delete(trackingKey) } returns Mono.just(1L)
        every { redis.delete(pendingAckKey) } returns Mono.just(1L)
        every { valueOps.get(busyKey) } returns Mono.just(busyJson(ticketId))
        every { redis.delete(busyKey) } returns Mono.just(1L)

        service.failDispatch(dispatchId, "ack_timeout")

        val event = slot<QueueSseEvent>()
        verify(exactly = 1) { redis.delete(busyKey) }
        verify(exactly = 1) { broadcaster.broadcast(capture(event)) }
        assertThat(event.captured.type).isEqualTo("DEVICE_DISPATCH_FAILED")
        assertThat(event.captured.reason).isEqualTo("ack_timeout")
        assertThat(event.captured.ticketId).isEqualTo(ticketId)
        assertThat(event.captured.ticketNumber).isEqualTo("A001")
    }

    @Test
    fun `failDispatch does not release the busy lock when it belongs to a newer ticket`() = runTest {
        val otherTicket = UUID.fromString("77777777-7777-7777-7777-777777777777")
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.just(trackingJson())
        every { redis.delete(trackingKey) } returns Mono.just(1L)
        every { redis.delete(pendingAckKey) } returns Mono.just(1L)
        every { valueOps.get(busyKey) } returns Mono.just(busyJson(otherTicket))

        service.failDispatch(dispatchId, "ack_timeout")

        verify(exactly = 0) { redis.delete(busyKey) }
        verify(exactly = 1) { broadcaster.broadcast(any()) }
    }

    @Test
    fun `failDispatch is idempotent when the record is already gone`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.empty()

        service.failDispatch(dispatchId, "ack_timeout")

        verify(exactly = 0) { broadcaster.broadcast(any()) }
        verify(exactly = 0) { redis.delete(busyKey) }
    }

    @Test
    fun `failDispatch no-ops when it loses the delete race`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.just(trackingJson())
        every { redis.delete(trackingKey) } returns Mono.just(0L)

        service.failDispatch(dispatchId, "ack_timeout")

        verify(exactly = 0) { broadcaster.broadcast(any()) }
        verify(exactly = 0) { redis.delete(busyKey) }
    }

    @Test
    fun `completeDispatch clears both keys without emitting or releasing busy`() = runTest {
        every { redis.delete(pendingAckKey) } returns Mono.just(1L)
        every { redis.delete(trackingKey) } returns Mono.just(1L)

        service.completeDispatch(dispatchId)

        verify(exactly = 1) { redis.delete(trackingKey) }
        verify(exactly = 1) { redis.delete(pendingAckKey) }
        verify(exactly = 0) { broadcaster.broadcast(any()) }
        verify(exactly = 0) { redis.delete(busyKey) }
    }

    @Test
    fun `failDispatch returns after claiming when the tracking record is malformed`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.just("{not json")
        every { redis.delete(trackingKey) } returns Mono.just(1L)
        every { redis.delete(pendingAckKey) } returns Mono.just(1L)

        service.failDispatch(dispatchId, "ack_timeout")

        verify(exactly = 0) { broadcaster.broadcast(any()) }
        verify(exactly = 0) { redis.delete(busyKey) }
    }

    @Test
    fun `failDispatch emits even when the busy key is already gone`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(trackingKey) } returns Mono.just(trackingJson())
        every { redis.delete(trackingKey) } returns Mono.just(1L)
        every { redis.delete(pendingAckKey) } returns Mono.just(1L)
        every { valueOps.get(busyKey) } returns Mono.empty()

        service.failDispatch(dispatchId, "ack_timeout")

        verify(exactly = 0) { redis.delete(busyKey) }
        verify(exactly = 1) { broadcaster.broadcast(any()) }
    }
}
