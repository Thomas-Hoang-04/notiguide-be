package com.thomas.notiguide.domain.device.listener

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceSignatureVerifier
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.service.DeviceLifecycleService
import com.thomas.notiguide.domain.device.service.DispatchReconciliationService
import com.thomas.notiguide.domain.device.service.HubDiagnosticsService
import com.thomas.notiguide.domain.device.service.TransmitterElectionService
import io.r2dbc.spi.Readable
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
@ConditionalOnBean(MqttClientManager::class)
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class TransmitterOperationalListener(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper,
    private val client: DatabaseClient,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val properties: DeviceTransmitterProperties,
    private val deviceLifecycleService: DeviceLifecycleService,
    private val hubDiagnosticsService: HubDiagnosticsService,
    private val electionService: TransmitterElectionService,
    private val dispatchReconciliationService: DispatchReconciliationService
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartbeatTopic by lazy { "${mqttProperties.topicPrefix}/transmitter/hub/+/heartbeat" }
    private val ackTopic by lazy { "${mqttProperties.topicPrefix}/transmitter/hub/+/ack" }

    private val handler = MqttMessageHandler { topic, message ->
        val payload = String(message.payload, Charsets.UTF_8)
        when {
            topic.endsWith("/heartbeat") -> {
                val publicId = topic.removePrefix("${mqttProperties.topicPrefix}/transmitter/hub/")
                    .removeSuffix("/heartbeat")
                if (publicId == topic) return@MqttMessageHandler
                scope.launch {
                    handleHeartbeat(publicId, payload)
                }
            }

            topic.endsWith("/ack") -> {
                val publicId = topic.removePrefix("${mqttProperties.topicPrefix}/transmitter/hub/")
                    .removeSuffix("/ack")
                if (publicId == topic) return@MqttMessageHandler
                scope.launch {
                    handleAck(publicId, payload)
                }
            }
        }
    }

    override fun afterSingletonsInstantiated() {
        mqttClientManager.registerHandler(handler)
        mqttClientManager.subscribe(heartbeatTopic, qos = 0)
        mqttClientManager.subscribe(ackTopic)
    }

    @PreDestroy
    fun destroy() {
        mqttClientManager.unsubscribe(heartbeatTopic)
        mqttClientManager.unsubscribe(ackTopic)
        mqttClientManager.removeHandler(handler)
        scope.cancel()
    }

    private suspend fun handleHeartbeat(publicId: String, payload: String) {
        val heartbeat = runCatching {
            objectMapper.readValue(payload, TransmitterHeartbeatEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed transmitter heartbeat for {}", publicId, it)
            return
        }

        if (heartbeat.schemaVersion != 1) {
            return
        }

        val keyDer = lookupHubKey(publicId) ?: run {
            log.warn("Heartbeat from hub {} with no stored public key; dropping", publicId); return
        }
        val issuedAtRaw = heartbeat.issuedAt ?: run {
            log.warn("Heartbeat from {} missing issued_at; dropping", publicId); return
        }
        if (!heartbeatFresh(issuedAtRaw, OffsetDateTime.now(ZoneOffset.UTC).toInstant(), HEARTBEAT_FRESHNESS_SECONDS)) {
            log.warn("Dropping stale/future/unparseable heartbeat for hub {}", publicId); return
        }
        val diag = heartbeat.diag
        val canonical = DeviceCanonical.heartbeat(
            hubPublicId = publicId,
            issuedAtRaw = issuedAtRaw,
            heapPct = diag?.freeHeapPct ?: 0,
            rssi = diag?.rssi?.toString() ?: "",
            uptimeMs = diag?.uptimeMs ?: 0L,
            dispD = (diag?.dispatchDaily ?: 0).toLong(),
            dispT = (diag?.dispatchTotal ?: 0).toLong(),
            ip = diag?.ip ?: ""
        )
        if (!DeviceSignatureVerifier.verify(keyDer, canonical, heartbeat.signatureB64)) {
            log.warn("Dropping heartbeat with invalid signature for hub {}", publicId); return
        }

        val serverNow = OffsetDateTime.now(ZoneOffset.UTC)
        val touched = touchHub(publicId, serverNow) ?: return
        redis.opsForValue()
            .set(
                RedisKeyManager.deviceHubAlive(touched.deviceId),
                "1",
                Duration.ofSeconds(properties.heartbeatLivenessSeconds)
            )
            .awaitSingle()

        if (touched.storeId != null) {
            val hasElected = redis.hasKey(RedisKeyManager.storeTransmitterActive(touched.storeId)).awaitSingle()
            if (!hasElected) {
                runCatching { electionService.electActive(touched.storeId) }
            }
        }

        heartbeat.diag?.let { diag ->
            hubDiagnosticsService.recordMqttDiagnostics(
                deviceId = touched.deviceId,
                freeHeapPct = diag.freeHeapPct,
                rssi = diag.rssi,
                uptimeMs = diag.uptimeMs,
                dispatchDaily = diag.dispatchDaily,
                dispatchTotal = diag.dispatchTotal,
                ip = diag.ip,
                seenAt = serverNow
            )
        }
    }

    private suspend fun handleAck(publicId: String, payload: String) {
        val ack = runCatching {
            objectMapper.readValue(payload, TransmitterAckEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed transmitter ack for {}", publicId, it)
            return
        }

        if (ack.schemaVersion != 1) {
            return
        }

        val keyDer = lookupHubKey(publicId) ?: run {
            log.warn("Ack from hub {} with no stored public key; dropping", publicId); return
        }
        val id = when (ack.ackFor) {
            "transmit" -> ack.dispatchId?.toString()
            "deact" -> ack.commandId?.toString()
            else -> null
        } ?: run { log.warn("Ack from {} missing id for ack_for={}", publicId, ack.ackFor); return }
        val canonical = DeviceCanonical.ack(publicId, ack.ackFor, id, ack.status)
        if (!DeviceSignatureVerifier.verify(keyDer, canonical, ack.signatureB64)) {
            log.warn("Dropping ack with invalid signature for hub {}", publicId); return
        }

        when {
            ack.ackFor == "deact" -> deviceLifecycleService.onAck(publicId, payload)
            else -> {
                val seenAt = ack.appliedAt ?: OffsetDateTime.now(ZoneOffset.UTC)
                // A failed liveness touch must not abort ack reconciliation — a skipped
                // completeDispatch would later surface a spurious ack_timeout failure.
                runCatching { touchHub(publicId, seenAt) }
                    .onFailure { log.warn("dispatch_ack_touch_failed publicId={}", publicId, it) }
                val dispatchId = ack.dispatchId
                if (dispatchId == null) {
                    log.warn("Dropping transmitter ack without dispatch_id for {}", publicId)
                    return
                }
                log.info(
                    "Transmitter dispatch ack: publicId={} dispatchId={} status={} reason={}",
                    publicId,
                    dispatchId,
                    ack.status,
                    ack.reason
                )
                if (ack.status == "applied" || ack.status == "unchanged") {
                    dispatchReconciliationService.completeDispatch(dispatchId)
                } else {
                    dispatchReconciliationService.failDispatch(
                        dispatchId,
                        "transmit_rejected:${ack.reason ?: ack.status}"
                    )
                }
            }
        }
    }

    private suspend fun touchHub(
        publicId: String,
        seenAt: OffsetDateTime
    ): HubTouchRecord? =
        client.sql(
            """
            UPDATE device
            SET last_seen_at = :seenAt
            WHERE public_id = :publicId
              AND kind = 'TRANSMITTER_HUB'
            RETURNING id, store_id
            """
        )
            .bind("publicId", publicId)
            .bind("seenAt", seenAt)
            .map(::mapHubTouch)
            .one()
            .awaitSingleOrNull()

    private fun mapHubTouch(row: Readable): HubTouchRecord = HubTouchRecord(
        deviceId = row.get("id", UUID::class.java)!!,
        storeId = row.get("store_id", UUID::class.java)
    )

    private suspend fun lookupHubKey(publicId: String): ByteArray? =
        client.sql(
            """
            SELECT public_key_der
            FROM device
            WHERE public_id = :publicId
              AND kind = 'TRANSMITTER_HUB'
            """
        )
            .bind("publicId", publicId)
            .map { row -> row.get("public_key_der", ByteArray::class.java) }
            .one()
            .awaitSingleOrNull()

    @Suppress("SameParameterValue")
    private fun heartbeatFresh(issuedAtRaw: String, now: Instant, windowSeconds: Long): Boolean {
        val issued = runCatching { OffsetDateTime.parse(issuedAtRaw).toInstant() }.getOrNull() ?: return false
        return Duration.between(issued, now).abs().seconds <= windowSeconds
    }

    companion object {
        const val HEARTBEAT_FRESHNESS_SECONDS = 120L
    }
}

private data class HubTouchRecord(
    val deviceId: UUID,
    val storeId: UUID?
)

private data class TransmitterHeartbeatEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("issued_at")
    val issuedAt: String? = null,
    val diag: HeartbeatDiagPayload? = null,
    @field:JsonProperty("signature_b64")
    val signatureB64: String = ""
)

private data class HeartbeatDiagPayload(
    @field:JsonProperty("heap_pct")
    val freeHeapPct: Int = 0,
    val rssi: Int? = null,
    @field:JsonProperty("uptime_ms")
    val uptimeMs: Long = 0,
    @field:JsonProperty("disp_d")
    val dispatchDaily: Int = 0,
    @field:JsonProperty("disp_t")
    val dispatchTotal: Int = 0,
    val ip: String? = null
)

private data class TransmitterAckEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("ack_for")
    val ackFor: String = "",
    @field:JsonProperty("dispatch_id")
    val dispatchId: UUID? = null,
    @field:JsonProperty("command_id")
    val commandId: UUID? = null,
    val status: String = "",
    val reason: String? = null,
    @field:JsonProperty("applied_at")
    val appliedAt: OffsetDateTime? = null,
    @field:JsonProperty("signature_b64")
    val signatureB64: String = ""
)
