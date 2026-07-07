package com.thomas.notiguide.domain.device.listener

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceCommandSigner
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DevicePublicIdMinter
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.types.DeviceKind
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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
@ConditionalOnBean(MqttClientManager::class)
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class RosterSyncListener(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper,
    private val client: DatabaseClient,
    private val publisherProvider: ObjectProvider<DeviceMqttPublisher>,
    private val signerProvider: ObjectProvider<DeviceCommandSigner>,
    private val publicIdMinter: DevicePublicIdMinter
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rosterTopic by lazy {
        "${mqttProperties.topicPrefix}/transmitter/hub/+/roster/update"
    }

    private val handler = MqttMessageHandler { topic, message ->
        if (!topic.contains("/roster/update")) return@MqttMessageHandler
        val publicId = topic.removePrefix("${mqttProperties.topicPrefix}/transmitter/hub/")
            .removeSuffix("/roster/update")
        if (publicId == topic) return@MqttMessageHandler

        val payload = String(message.payload, Charsets.UTF_8)
        scope.launch {
            runCatching { handleRosterUpdate(publicId, payload) }
                .onFailure { ex ->
                    log.warn("Roster sync handling failed for hub {}", publicId, ex)
                }
        }
    }

    override fun afterSingletonsInstantiated() {
        mqttClientManager.registerHandler(handler)
        mqttClientManager.subscribe(rosterTopic)
    }

    @PreDestroy
    fun destroy() {
        mqttClientManager.unsubscribe(rosterTopic)
        mqttClientManager.removeHandler(handler)
        scope.cancel()
    }

    private suspend fun handleRosterUpdate(publicId: String, payload: String) {
        val roster = runCatching {
            objectMapper.readValue(payload, RosterUpdateEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed roster update for hub {}", publicId, it)
            return
        }

        if (roster.schemaVersion != 1) {
            log.debug("Ignoring roster update with unsupported schema_version={} for hub {}", roster.schemaVersion, publicId)
            return
        }

        val hub = lookupHub(publicId)
        if (hub == null) {
            log.warn("Roster update from unknown hub publicId={}", publicId)
            return
        }

        // Idempotency: if seq <= lastRosterSeq, re-ACK without processing
        if (hub.lastRosterSeq != null && roster.seq <= hub.lastRosterSeq) {
            log.debug("Duplicate roster seq={} for hub {} (lastRosterSeq={}), re-ACKing", roster.seq, publicId, hub.lastRosterSeq)
            publishAck(publicId, roster.seq)
            return
        }

        // Upsert each receiver in the roster
        val activeSlots = mutableListOf<Short>()
        for (receiver in roster.receivers) {
            val slot = receiver.slot.toShort()
            val kind = bandToKind(receiver.band)

            if (kind == null) {
                log.warn("Skipping roster receiver with unknown band={} slot={} for hub {}", receiver.band, receiver.slot, publicId)
                continue
            }

            upsertRosterReceiver(
                storeId = hub.storeId,
                hubSlot = slot,
                kind = kind,
                label = receiver.label
            )
            activeSlots.add(slot)
        }

        // Delete devices paired to this store whose hub_slot is not in the active roster
        if (hub.storeId != null) {
            deleteUnpairedReceivers(hub.storeId, activeSlots)
        }

        // Update last_roster_seq on the hub device
        updateLastRosterSeq(publicId, roster.seq)

        log.info("Roster sync complete for hub {} seq={} receivers={}", publicId, roster.seq, activeSlots.size)

        // Sign and publish ACK
        publishAck(publicId, roster.seq)
    }

    private suspend fun lookupHub(publicId: String): HubRecord? =
        client.sql(
            """
            SELECT id, store_id, last_roster_seq
            FROM device
            WHERE public_id = :publicId
              AND kind = 'TRANSMITTER_HUB'
              AND status = 'ACTIVE'
            """
        )
            .bind("publicId", publicId)
            .map(::mapHubRecord)
            .one()
            .awaitSingleOrNull()

    private fun mapHubRecord(row: Readable): HubRecord = HubRecord(
        deviceId = row.get("id", UUID::class.java)!!,
        storeId = row.get("store_id", UUID::class.java),
        lastRosterSeq = row.get("last_roster_seq", Int::class.javaObjectType)
    )

    private suspend fun upsertRosterReceiver(
        storeId: UUID?,
        hubSlot: Short,
        kind: DeviceKind,
        label: String?
    ) {
        if (storeId == null) return

        val existing = client.sql(
            """
            SELECT id, public_id FROM device
            WHERE store_id = :storeId
              AND hub_slot = :hubSlot
            LIMIT 1
            """
        )
            .bind("storeId", storeId)
            .bind("hubSlot", hubSlot)
            .map(::mapRosterReceiverRecord)
            .one()
            .awaitSingleOrNull()

        if (existing != null) {
            val publicId = existing.publicId ?: publicIdMinter.mint(kind)
            client.sql(
                """
                UPDATE device
                SET kind = :kind::device_kind,
                    status = 'ACTIVE'::device_status,
                    assigned_name = :label,
                    public_id = :publicId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """
            )
                .bind("id", existing.deviceId)
                .bind("kind", kind.name)
                .bind("label", label ?: "Slot $hubSlot")
                .bind("publicId", publicId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else {
            client.sql(
                """
                INSERT INTO device (hub_slot, kind, status, assigned_name, store_id, public_id)
                VALUES (:hubSlot, :kind::device_kind, 'ACTIVE'::device_status, :label, :storeId, :publicId)
                """
            )
                .bind("hubSlot", hubSlot)
                .bind("kind", kind.name)
                .bind("label", label ?: "Slot $hubSlot")
                .bind("storeId", storeId)
                .bind("publicId", publicIdMinter.mint(kind))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun mapRosterReceiverRecord(row: Readable): RosterReceiverRecord = RosterReceiverRecord(
        deviceId = row.get("id", UUID::class.java)!!,
        publicId = row.get("public_id", String::class.java)
    )

    private suspend fun deleteUnpairedReceivers(storeId: UUID, activeSlots: List<Short>) {
        if (activeSlots.isEmpty()) {
            // All receivers unpaired — delete all hub-paired devices for this store
            client.sql(
                """
                DELETE FROM device
                WHERE store_id = :storeId
                  AND hub_slot IS NOT NULL
                """
            )
                .bind("storeId", storeId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else {
            // R2DBC DatabaseClient does not support list binding for NOT IN;
            // build parameterized placeholders dynamically
            val placeholders = activeSlots.indices.joinToString(", ") { ":slot$it" }
            var spec = client.sql(
                """
                DELETE FROM device
                WHERE store_id = :storeId
                  AND hub_slot IS NOT NULL
                  AND hub_slot NOT IN ($placeholders)
                """
            )
                .bind("storeId", storeId)

            activeSlots.forEachIndexed { idx, slot ->
                spec = spec.bind("slot$idx", slot)
            }

            spec.fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private suspend fun updateLastRosterSeq(publicId: String, seq: Int) {
        client.sql(
            """
            UPDATE device
            SET last_roster_seq = :seq,
                updated_at = CURRENT_TIMESTAMP
            WHERE public_id = :publicId
              AND kind = 'TRANSMITTER_HUB'
            """
        )
            .bind("publicId", publicId)
            .bind("seq", seq)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun publishAck(hubPublicId: String, seq: Int) {
        val signer = signerProvider.ifAvailable ?: run {
            log.warn("DeviceCommandSigner not available, skipping roster ACK for hub {}", hubPublicId)
            return
        }
        val publisher = publisherProvider.ifAvailable ?: run {
            log.warn("DeviceMqttPublisher not available, skipping roster ACK for hub {}", hubPublicId)
            return
        }

        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val canonical = DeviceCanonical.rosterAck(hubPublicId, seq, issuedAt)
        val signatureB64 = signer.sign(canonical)

        val ackEnvelope = RosterAckEnvelope(
            seq = seq,
            issuedAt = issuedAt.toInstant().toString(),
            signatureB64 = signatureB64
        )

        publisher.publishRosterAck(hubPublicId, ackEnvelope)
        log.debug("Roster ACK published for hub {} seq={}", hubPublicId, seq)
    }

    private fun bandToKind(band: String): DeviceKind? = when (band) {
        "433M" -> DeviceKind.RECEIVER_433M
        "2_4G" -> DeviceKind.RECEIVER_2_4G
        else -> null
    }

}

private data class HubRecord(
    val deviceId: UUID,
    val storeId: UUID?,
    val lastRosterSeq: Int?
)

private data class RosterReceiverRecord(
    val deviceId: UUID,
    val publicId: String?
)

private data class RosterUpdateEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    val seq: Int = 0,
    val receivers: List<RosterReceiverEntry> = emptyList()
)

private data class RosterReceiverEntry(
    val slot: Int = 0,
    val band: String = "",
    val label: String? = null
)

private data class RosterAckEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    val seq: Int,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)
