package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.dto.HubDiagnosticsDto
import com.thomas.notiguide.domain.device.types.HubDiagnosticsSource
import com.thomas.notiguide.domain.device.request.DeviceDiagnosticsRelayRequest
import com.thomas.notiguide.domain.device.response.HubHealthSummaryResponse
import com.thomas.notiguide.domain.device.dto.HubWarningDto
import com.thomas.notiguide.domain.device.types.HubWarningType
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class HubDiagnosticsService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val properties: DeviceTransmitterProperties,
    private val client: DatabaseClient
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun loadDiagnostics(deviceId: UUID): HubDiagnosticsDto? {
        val payload = runCatching {
            redis.opsForValue()
                .get(RedisKeyManager.deviceHubDiagnostics(deviceId))
                .awaitSingleOrNull()
        }.getOrNull() ?: return null
        return runCatching {
            objectMapper.readValue(payload, HubDiagnosticsDto::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed diagnostics cache for device {}", deviceId)
            null
        }
    }

    suspend fun recordMqttDiagnostics(
        deviceId: UUID,
        freeHeapPct: Int,
        rssi: Int?,
        uptimeMs: Long,
        dispatchDaily: Int,
        dispatchTotal: Int,
        ip: String?,
        seenAt: OffsetDateTime
    ) {
        val record = HubDiagnosticsDto(
            freeHeapPct = freeHeapPct,
            rssi = rssi,
            uptimeMs = uptimeMs,
            dispatchDaily = dispatchDaily,
            dispatchTotal = dispatchTotal,
            wifiConnected = null,
            ip = ip,
            firmwareVersion = null,
            source = HubDiagnosticsSource.MQTT,
            updatedAt = seenAt
        )
        redis.opsForValue()
            .set(
                RedisKeyManager.deviceHubDiagnostics(deviceId),
                objectMapper.writeValueAsString(record),
                Duration.ofSeconds(properties.diagnosticsCacheSeconds)
            )
            .awaitSingle()
    }

    suspend fun relayUsbDiagnostics(device: DeviceDetailDto, request: DeviceDiagnosticsRelayRequest) {
        if (device.kind != DeviceKind.TRANSMITTER_HUB) {
            throw ForbiddenException("Diagnostics relay is only supported for transmitter hubs")
        }
        if (device.status == DeviceStatus.REJECTED || device.status == DeviceStatus.DECOMMISSIONED) {
            throw ForbiddenException("Cannot relay diagnostics for a ${device.status.name.lowercase()} device")
        }
        if (device.publicId == null || request.publicId != device.publicId) {
            throw ForbiddenException("Relay public ID does not match device record")
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val record = HubDiagnosticsDto(
            freeHeapPct = request.freeHeapPct,
            rssi = request.rssi,
            uptimeMs = request.uptimeMs,
            dispatchDaily = request.dispatchDaily,
            dispatchTotal = request.dispatchTotal,
            wifiConnected = request.wifiConnected,
            ip = request.ip,
            firmwareVersion = request.firmwareVersion,
            source = HubDiagnosticsSource.USB,
            updatedAt = now
        )

        redis.opsForValue()
            .set(
                RedisKeyManager.deviceHubDiagnostics(device.id),
                objectMapper.writeValueAsString(record),
                Duration.ofSeconds(properties.diagnosticsCacheSeconds)
            )
            .awaitSingle()

        redis.opsForValue()
            .set(
                RedisKeyManager.deviceHubAlive(device.id),
                "1",
                Duration.ofSeconds(properties.heartbeatLivenessSeconds)
            )
            .awaitSingle()

        val updateSql = if (!request.firmwareVersion.isNullOrBlank()) {
            "UPDATE device SET last_seen_at = :now, firmware_version = :fw WHERE id = :id"
        } else {
            "UPDATE device SET last_seen_at = :now WHERE id = :id"
        }

        val spec = client.sql(updateSql)
            .bind("now", now)
            .bind("id", device.id)
        val bound = if (!request.firmwareVersion.isNullOrBlank()) {
            spec.bind("fw", request.firmwareVersion)
        } else {
            spec
        }
        bound.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun getHubHealthSummary(storeId: UUID?): HubHealthSummaryResponse {
        val hubs = client.sql(
            """
            SELECT id, assigned_name
            FROM device
            WHERE kind = 'TRANSMITTER_HUB'
              AND status = 'ACTIVE'
              ${if (storeId != null) "AND store_id = :storeId" else ""}
            """
        )
            .let { if (storeId != null) it.bind("storeId", storeId) else it }
            .map { row: Readable ->
                row.get("id", UUID::class.java)!! to row.get("assigned_name", String::class.java)
            }
            .all()
            .collectList()
            .awaitSingle()

        var onlineCount = 0
        val warnings = mutableListOf<HubWarningDto>()

        for ((hubId, hubName) in hubs) {
            val alive = redis.hasKey(RedisKeyManager.deviceHubAlive(hubId)).awaitSingle()
            if (!alive) continue
            onlineCount++

            val diag = loadDiagnostics(hubId) ?: continue
            if (diag.freeHeapPct <= 35) {
                warnings.add(HubWarningDto(hubId, hubName, HubWarningType.LOW_MEMORY, "${diag.freeHeapPct}%"))
            }
            if (diag.rssi != null && diag.rssi < -65) {
                warnings.add(HubWarningDto(hubId, hubName, HubWarningType.WEAK_SIGNAL, "${diag.rssi} dBm"))
            }
            val uptimeDays = diag.uptimeMs / (24 * 3600 * 1000L)
            if (uptimeDays >= 7) {
                warnings.add(HubWarningDto(hubId, hubName, HubWarningType.LONG_UPTIME, "${uptimeDays}d"))
            }
        }

        return HubHealthSummaryResponse(
            totalHubs = hubs.size,
            onlineHubs = onlineCount,
            offlineHubs = hubs.size - onlineCount,
            warnings = warnings
        )
    }
}
