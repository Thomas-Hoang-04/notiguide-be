package com.thomas.notiguide.domain.device.listener

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.service.DeviceLifecycleService
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
    private val electionService: TransmitterElectionService
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

        when {
            ack.schemaVersion != 1 -> return
            ack.ackFor == "deact" -> deviceLifecycleService.onAck(publicId, payload)
            ack.ackFor == "transmit" -> {
                val seenAt = ack.appliedAt ?: OffsetDateTime.now(ZoneOffset.UTC)
                touchHub(publicId, seenAt)
                if (ack.dispatchId == null) {
                    log.warn("Dropping transmitter ack without dispatch_id for {}", publicId)
                    return
                }
                log.info(
                    "Transmitter dispatch ack: publicId={} dispatchId={} status={} reason={}",
                    publicId,
                    ack.dispatchId,
                    ack.status,
                    ack.reason
                )
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
}

private data class HubTouchRecord(
    val deviceId: UUID,
    val storeId: UUID?
)

private data class TransmitterHeartbeatEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("issued_at")
    val issuedAt: OffsetDateTime? = null,
    val diag: HeartbeatDiagPayload? = null
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
    val status: String = "",
    val reason: String? = null,
    @field:JsonProperty("applied_at")
    val appliedAt: OffsetDateTime? = null
)
