package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.dto.BoundTicketDto
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.dto.DeviceDto
import com.thomas.notiguide.domain.device.dto.DeviceLifecycleCommandDto
import com.thomas.notiguide.domain.device.response.DeviceListResponse
import com.thomas.notiguide.domain.device.dto.DeviceRfCodeSummaryDto
import com.thomas.notiguide.domain.device.redis.DeviceBusyRecord
import com.thomas.notiguide.domain.device.redis.DeviceLifecycleCommandRecord
import com.thomas.notiguide.domain.device.redis.TransmitterActiveRecord
import com.thomas.notiguide.domain.device.types.DeviceHardwareModel
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import com.thomas.notiguide.domain.device.types.DeviceStatus
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class DeviceQueryService(
    private val client: DatabaseClient,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    suspend fun listDevices(
        kind: DeviceKind?,
        storeId: UUID?
    ): DeviceListResponse {
        val includeRegisteredCount = kind == DeviceKind.TRANSMITTER_HUB && storeId != null
        val rows = client.sql(
            """
            SELECT
                d.id,
                d.public_id,
                d.hardware_model,
                d.kind,
                d.status,
                d.assigned_name,
                d.store_id,
                s.name AS store_name,
                d.firmware_version,
                d.last_seen_at,
                d.activated_at,
                d.created_at,
                d.updated_at,
                r.bits AS rf_bits,
                r.byte_len AS rf_byte_len,
                r.version AS rf_version,
                r.ack AS rf_ack,
                r.issued_at AS rf_issued_at,
                r.ack_at AS rf_ack_at,
                CASE
                    WHEN :includeRegisteredCount THEN
                        COUNT(*) FILTER (WHERE d.status NOT IN ('DECOMMISSIONED', 'REJECTED')) OVER ()
                END AS registered_count
            FROM device d
            LEFT JOIN store s ON s.id = d.store_id
            LEFT JOIN device_rf_code r ON r.device_id = d.id
            WHERE (:kindFilter IS NULL OR d.kind = CAST(:kindFilter AS device_kind))
              AND (:storeId IS NULL OR d.store_id = :storeId)
            ORDER BY d.created_at DESC
            """
        )
            .bind("includeRegisteredCount", includeRegisteredCount)
            .bindNullable("kindFilter", kind?.name, String::class.java)
            .bindNullable("storeId", storeId, UUID::class.java)
            .map(::mapRow)
            .all()
            .asFlow()
            .toList()

        val registered = if (includeRegisteredCount) {
            rows.firstOrNull()?.registeredCount ?: 0L
        } else {
            null
        }

        return DeviceListResponse(
            devices = rows.map { it.device },
            registered = registered
        )
    }

    suspend fun getRequiredDeviceById(deviceId: UUID): DeviceDto =
        findDeviceById(deviceId) ?: throw IllegalStateException("Newly-created device '$deviceId' could not be reloaded")

    suspend fun getRequiredDeviceDetailById(deviceId: UUID): DeviceDetailDto =
        findDeviceDetailById(deviceId)
            ?: throw IllegalStateException("Device '$deviceId' could not be reloaded")

    suspend fun findDeviceDetailById(deviceId: UUID): DeviceDetailDto? {
        val device = findDeviceById(deviceId) ?: return null
        val lifecycle = loadLifecycleCommand(deviceId)
        val isElected = if (device.kind.isHub() && device.storeId != null) {
            loadElectedHubId(device.storeId) == deviceId
        } else null
        val boundTicket = if (!device.kind.isHub()) {
            loadBoundTicket(deviceId, device.storeId)
        } else null
        return device.toDetail(lifecycle, isElected, boundTicket)
    }

    private suspend fun loadElectedHubId(storeId: UUID): UUID? {
        val payload = runCatching {
            redis.opsForValue()
                .get(RedisKeyManager.storeTransmitterActive(storeId))
                .awaitSingleOrNull()
        }.getOrNull() ?: return null
        return runCatching {
            objectMapper.readValue(payload, TransmitterActiveRecord::class.java).deviceId
        }.getOrNull()
    }

    private suspend fun loadBoundTicket(deviceId: UUID, storeId: UUID?): BoundTicketDto? {
        if (storeId == null) return null
        val busyPayload = runCatching {
            redis.opsForValue()
                .get(RedisKeyManager.deviceBusy(deviceId))
                .awaitSingleOrNull()
        }.getOrNull() ?: return null
        val busy = runCatching {
            objectMapper.readValue(busyPayload, DeviceBusyRecord::class.java)
        }.getOrNull() ?: return null
        if (busy.ticketId == UUID(0L, 0L)) return null

        val ticketPayload = runCatching {
            redis.opsForHash<String, String>()
                .entries(RedisKeyManager.ticket(storeId, busy.ticketId))
                .collectList()
                .awaitSingleOrNull()
        }.getOrNull()?.associate { it.key to it.value } ?: return null

        val number = ticketPayload["number"] ?: return null
        val status = ticketPayload["status"] ?: "UNKNOWN"
        return BoundTicketDto(
            ticketId = busy.ticketId,
            ticketNumber = number,
            status = status
        )
    }

    suspend fun findDeviceById(deviceId: UUID): DeviceDto? =
        client.sql(
            """
            SELECT
                d.id,
                d.public_id,
                d.hardware_model,
                d.kind,
                d.status,
                d.assigned_name,
                d.store_id,
                s.name AS store_name,
                d.firmware_version,
                d.last_seen_at,
                d.activated_at,
                d.created_at,
                d.updated_at,
                r.bits AS rf_bits,
                r.byte_len AS rf_byte_len,
                r.version AS rf_version,
                r.ack AS rf_ack,
                r.issued_at AS rf_issued_at,
                r.ack_at AS rf_ack_at,
                NULL::bigint AS registered_count
            FROM device d
            LEFT JOIN store s ON s.id = d.store_id
            LEFT JOIN device_rf_code r ON r.device_id = d.id
            WHERE d.id = :deviceId
            """
        )
            .bind("deviceId", deviceId)
            .map(::mapRow)
            .one()
            .awaitSingleOrNull()
            ?.device

    private suspend fun loadLifecycleCommand(deviceId: UUID): DeviceLifecycleCommandDto? {
        val payload = runCatching {
            redis.opsForValue()
                .get(RedisKeyManager.deviceLifecycleCommand(deviceId))
                .awaitSingleOrNull()
        }.getOrNull() ?: return null

        val command = runCatching {
            objectMapper.readValue(payload, DeviceLifecycleCommandRecord::class.java)
        }.getOrNull() ?: return null

        return DeviceLifecycleCommandDto(
            commandId = command.commandId,
            action = command.action,
            ackStatus = command.ackStatus,
            issuedAt = command.issuedAt
        )
    }

    private fun mapRow(row: Readable): DeviceRow = DeviceRow(
        device = DeviceDto(
            id = row.get("id", UUID::class.java)!!,
            publicId = row.get("public_id", String::class.java),
            hardwareModel = row.get("hardware_model", DeviceHardwareModel::class.java)!!,
            kind = row.get("kind", DeviceKind::class.java)!!,
            status = row.get("status", DeviceStatus::class.java)!!,
            assignedName = row.get("assigned_name", String::class.java),
            storeId = row.get("store_id", UUID::class.java),
            storeName = row.get("store_name", String::class.java),
            firmwareVersion = row.get("firmware_version", String::class.java),
            lastSeenAt = row.get("last_seen_at", OffsetDateTime::class.java),
            activatedAt = row.get("activated_at", OffsetDateTime::class.java),
            createdAt = row.get("created_at", OffsetDateTime::class.java),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java),
            rfCode = row.get("rf_version", Int::class.java)?.let {
                DeviceRfCodeSummaryDto(
                    bits = row.get("rf_bits", Short::class.java)!!.toInt(),
                    byteLen = row.get("rf_byte_len", Short::class.java)!!.toInt(),
                    version = it,
                    ack = row.get("rf_ack", DeviceRfAckStatus::class.java)!!,
                    issuedAt = row.get("rf_issued_at", OffsetDateTime::class.java)!!,
                    ackAt = row.get("rf_ack_at", OffsetDateTime::class.java)
                )
            }
        ),
        registeredCount = row.get("registered_count", Long::class.java)
    )
}

private fun DeviceDto.toDetail(
    lifecycleCommand: DeviceLifecycleCommandDto?,
    isElected: Boolean? = null,
    boundTicket: BoundTicketDto? = null
): DeviceDetailDto =
    DeviceDetailDto(
        id = id,
        publicId = publicId,
        hardwareModel = hardwareModel,
        kind = kind,
        status = status,
        assignedName = assignedName,
        storeId = storeId,
        storeName = storeName,
        firmwareVersion = firmwareVersion,
        lastSeenAt = lastSeenAt,
        activatedAt = activatedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        rfCode = rfCode,
        lifecycleCommand = lifecycleCommand,
        isElected = isElected,
        boundTicket = boundTicket
    )

private data class DeviceRow(
    val device: DeviceDto,
    val registeredCount: Long?
)

private fun <T> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>
): DatabaseClient.GenericExecuteSpec =
    if (value == null) bindNull(name, type) else bind(name, value)
