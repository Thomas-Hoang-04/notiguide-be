package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.domain.device.controller.DeviceConflictEnvelopeException
import com.thomas.notiguide.domain.device.dto.DeviceDto
import com.thomas.notiguide.domain.device.redis.DeviceBusyRecord
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.domain.queue.response.QueueDispatchAvailabilityResponse
import com.thomas.notiguide.domain.queue.dto.TicketDto
import com.thomas.notiguide.domain.queue.service.QueueService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class DeviceDispatchService(
    private val deviceQueryService: DeviceQueryService,
    private val queueService: QueueService,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val transmitterElectionServiceProvider: ObjectProvider<TransmitterElectionService>,
    private val transmitterPropertiesProvider: ObjectProvider<DeviceTransmitterProperties>
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun issueDeviceTicket(
        storeId: UUID,
        deviceId: UUID,
        serviceTypeId: UUID?,
        allowSerialFallback: Boolean = false
    ): TicketDto {
        val elected = transmitterElectionServiceProvider.ifAvailable?.electActive(storeId)
        if (elected == null && !allowSerialFallback) {
            throw DeviceConflictEnvelopeException("no_active_transmitter")
        }
        val device = loadDispatchableDevice(storeId, deviceId)

        val busyKey = RedisKeyManager.deviceBusy(device.id)
        val boundAt = OffsetDateTime.now(ZoneOffset.UTC)
        val busyPayload = objectMapper.writeValueAsString(
            DeviceBusyRecord(
                storeId = storeId,
                ticketId = UUID(0L, 0L),
                boundAt = boundAt
            )
        )

        val reserved = redis.opsForValue()
            .setIfAbsent(busyKey, busyPayload, RedisTTLPolicy.TICKET_WAITING)
            .awaitSingle()
        if (!reserved) {
            throw DeviceConflictEnvelopeException("device_busy")
        }

        return try {
            val ticket = queueService.issueTicket(
                storeId = storeId,
                serviceTypeId = serviceTypeId,
                extraFields = mapOf("device_id" to device.id.toString())
            )

            runCatching {
                val actualBusyPayload = objectMapper.writeValueAsString(
                    DeviceBusyRecord(
                        storeId = storeId,
                        ticketId = ticket.id,
                        boundAt = boundAt
                    )
                )
                redis.opsForValue()
                    .set(busyKey, actualBusyPayload, RedisTTLPolicy.TICKET_WAITING)
                    .awaitSingle()
            }.onFailure { ex ->
                log.warn("Failed to update busy-ticket binding for device {}", device.id, ex)
            }

            ticket.copy(deviceId = device.id, deviceName = device.assignedName)
        } catch (ex: Exception) {
            redis.delete(busyKey).awaitSingleOrNull()
            throw ex
        }
    }

    suspend fun getAvailableDevices(storeId: UUID): QueueDispatchAvailabilityResponse {
        val dispatchReady = transmitterElectionServiceProvider.ifAvailable?.electActive(storeId) != null
        val devices = deviceQueryService.listDevices(kind = null, storeId = storeId, orgId = null).devices
            .filter { device ->
                isDispatchableDevice(device, storeId) && !isBusy(device.id)
            }

        return QueueDispatchAvailabilityResponse(
            devices = devices,
            dispatchReady = dispatchReady,
            error = if (dispatchReady) null else "no_active_transmitter",
            maxHubsPerStore = transmitterPropertiesProvider.ifAvailable?.maxRegisteredPerStore
        )
    }

    private suspend fun loadDispatchableDevice(
        storeId: UUID,
        deviceId: UUID
    ): DeviceDto {
        val device = deviceQueryService.findDeviceById(deviceId)
            ?: throw DeviceConflictEnvelopeException("device_not_dispatchable")

        if (!isDispatchableDevice(device, storeId)) {
            throw DeviceConflictEnvelopeException("device_not_dispatchable")
        }
        if (isBusy(device.id)) {
            throw DeviceConflictEnvelopeException("device_busy")
        }
        return device
    }

    private fun isDispatchableDevice(
        device: DeviceDto,
        storeId: UUID
    ): Boolean =
        device.storeId == storeId &&
            !device.kind.isHub() &&
            device.status == DeviceStatus.ACTIVE &&
            (device.rfCode != null || device.hubSlot != null)

    private suspend fun isBusy(deviceId: UUID): Boolean =
        redis.hasKey(RedisKeyManager.deviceBusy(deviceId)).awaitSingle()
}
