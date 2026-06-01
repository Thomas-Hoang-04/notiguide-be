package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceCommandSigner
import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.mqtt.MqttPublisher
import com.thomas.notiguide.core.mqtt.MqttPublisher.QueueEvent
import com.thomas.notiguide.core.mqtt.MqttPublisher.QueueEventType
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.sse.QueueEventBroadcaster
import com.thomas.notiguide.core.sse.QueueSseEvent
import com.thomas.notiguide.domain.device.dto.DispatchTrackingRecord
import com.thomas.notiguide.domain.device.dto.DeviceDispatchEvent
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.types.DeviceDispatchEventType
import com.thomas.notiguide.domain.device.types.DeviceDispatchStopDisposition
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.Disposable
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class TransmitterDispatchService(
    private val deviceDispatchEventBroadcaster: DeviceDispatchEventBroadcaster,
    private val transmitterElectionService: TransmitterElectionService,
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val queueEventBroadcaster: QueueEventBroadcaster,
    private val properties: DeviceCommandSigningProperties,
    private val signerProvider: ObjectProvider<DeviceCommandSigner>,
    private val publisherProvider: ObjectProvider<DeviceMqttPublisher>,
    private val objectMapper: ObjectMapper,
    private val mqttPublisher: MqttPublisher? = null
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subscription: Disposable? = null

    override fun afterSingletonsInstantiated() {
        subscription = deviceDispatchEventBroadcaster.stream().subscribe { event ->
            scope.launch {
                handle(event)
            }
        }
    }

    @PreDestroy
    fun destroy() {
        subscription?.dispose()
        scope.cancel()
    }

    private suspend fun handle(event: DeviceDispatchEvent) {
        val hub = transmitterElectionService.electActive(event.storeId)
        if (hub == null) {
            log.warn(
                "transmitter_dispatch_election_lost store={} ticket={} device={}",
                event.storeId,
                event.ticketId,
                event.deviceId
            )
            emitDispatchFailed(event, "no_active_transmitter")
            return
        }

        val receiver = deviceRepository.findById(event.deviceId)
        val hubPublicId = hub.publicId
        if (receiver == null || hubPublicId == null) {
            log.warn(
                "transmitter_dispatch_failed_missing_device store={} ticket={} device={} hub={}",
                event.storeId,
                event.ticketId,
                event.deviceId,
                hub.id
            )
            emitDispatchFailed(event, "device_not_found")
            return
        }

        // Hub-paired receivers: slot-based dispatch (no RF code lookup needed)
        if (receiver.hubSlot != null) {
            handleSlotDispatch(hub, receiver, event)
            return
        }

        // Full-payload dispatch requires receiver publicId
        if (receiver.publicId == null) {
            log.warn(
                "transmitter_dispatch_failed_no_public_id store={} ticket={} device={}",
                event.storeId,
                event.ticketId,
                event.deviceId
            )
            emitDispatchFailed(event, "device_not_found")
            return
        }

        val signer = signerProvider.ifAvailable
        val publisher = publisherProvider.ifAvailable
        val decrypted = runCatching {
            deviceRfCodeRepository.findDecryptedPayload(receiver.id!!)
        }.getOrElse {
            log.warn(
                "transmitter_dispatch_failed_decrypt store={} ticket={} device={}",
                event.storeId,
                event.ticketId,
                event.deviceId,
                it
            )
            emitDispatchFailed(event, "rf_code_error")
            return
        }

        if (signer == null || publisher == null || decrypted == null) {
            log.warn(
                "transmitter_dispatch_failed_unavailable store={} ticket={} device={} signer={} publisher={} rfCode={}",
                event.storeId,
                event.ticketId,
                event.deviceId,
                signer != null,
                publisher != null,
                decrypted != null
            )
            emitDispatchFailed(event, "infrastructure_unavailable")
            return
        }

        val dispatchId = UUID.randomUUID()
        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC)

        val payload = runCatching {
            buildPayload(
                hubPublicId = hubPublicId,
                receiver = receiver,
                decrypted = decrypted,
                event = event,
                dispatchId = dispatchId,
                issuedAt = issuedAt,
                signer = signer
            )
        }.getOrElse {
            log.warn(
                "transmitter_dispatch_failed_payload store={} ticket={} device={}",
                event.storeId,
                event.ticketId,
                event.deviceId,
                it
            )
            emitDispatchFailed(event, "payload_error")
            return
        }

        runCatching {
            publisher.publishTransmit(hubPublicId, payload)
        }.onFailure { ex ->
            log.warn(
                "transmitter_dispatch_failed_publish store={} ticket={} device={} dispatch={}",
                event.storeId,
                event.ticketId,
                event.deviceId,
                dispatchId,
                ex
            )
            emitDispatchFailed(event, "publish_failed")
            return
        }

        runCatching {
            val trackingPayload = objectMapper.writeValueAsString(
                DispatchTrackingRecord(
                    deviceId = event.deviceId,
                    storeId = event.storeId,
                    ticketId = event.ticketId,
                    ticketNumber = event.ticketNumber
                )
            )
            redis.opsForValue()
                .set(
                    RedisKeyManager.dispatchTracking(dispatchId),
                    trackingPayload,
                    Duration.ofMinutes(5)
                )
                .awaitSingleOrNull()
        }.onFailure { ex ->
            log.warn("dispatch_tracking_write_failed dispatch={}", dispatchId, ex)
        }

        if (event.type == DeviceDispatchEventType.DEVICE_STOP_REQUESTED &&
            event.disposition == DeviceDispatchStopDisposition.RELEASE
        ) {
            runCatching {
                redis.delete(RedisKeyManager.deviceBusy(event.deviceId)).awaitSingleOrNull()
            }.onFailure { ex ->
                log.warn(
                    "transmitter_dispatch_busy_release_failed store={} ticket={} device={}",
                    event.storeId,
                    event.ticketId,
                    event.deviceId,
                    ex
                )
            }
        }
    }

    private suspend fun emitDispatchFailed(event: DeviceDispatchEvent, reason: String) {
        runCatching {
            mqttPublisher?.publishQueueEvent(
                event.storeId,
                QueueEvent(
                    type = QueueEventType.DEVICE_DISPATCH_FAILED,
                    storeId = event.storeId,
                    ticketId = event.ticketId,
                    ticketNumber = event.ticketNumber
                )
            )
        }.onFailure { ex ->
            log.warn(
                "Failed to publish DEVICE_DISPATCH_FAILED over MQTT: store={} ticket={}",
                event.storeId,
                event.ticketId,
                ex
            )
        }
        queueEventBroadcaster.broadcast(
            QueueSseEvent(
                type = QueueEventType.DEVICE_DISPATCH_FAILED.name,
                storeId = event.storeId,
                ticketId = event.ticketId,
                ticketNumber = event.ticketNumber,
                reason = reason
            )
        )
    }

    private suspend fun handleSlotDispatch(
        hub: Device,
        receiver: Device,
        event: DeviceDispatchEvent
    ) {
        val hubPublicId = hub.publicId ?: return
        val signer = signerProvider.ifAvailable ?: run {
            emitDispatchFailed(event, "infrastructure_unavailable")
            return
        }
        val publisher = publisherProvider.ifAvailable ?: run {
            emitDispatchFailed(event, "infrastructure_unavailable")
            return
        }

        val dispatchId = UUID.randomUUID()
        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val action = when (event.type) {
            DeviceDispatchEventType.DEVICE_CALL_REQUESTED -> "call"
            DeviceDispatchEventType.DEVICE_STOP_REQUESTED -> "stop"
        }

        val canonical = DeviceCanonical.slotDispatch(
            hubPublicId = hubPublicId,
            dispatchId = dispatchId,
            slot = receiver.hubSlot!!.toInt(),
            action = action,
            issuedAt = issuedAt
        )

        val envelope = SlotDispatchEnvelope(
            dispatchId = dispatchId,
            slot = receiver.hubSlot.toInt(),
            action = action,
            issuedAt = issuedAt.toInstant().toString(),
            signatureB64 = signer.sign(canonical)
        )

        runCatching {
            publisher.publishTransmit(hubPublicId, envelope)
        }.onFailure { ex ->
            log.warn(
                "transmitter_slot_dispatch_failed_publish store={} ticket={} slot={}",
                event.storeId, event.ticketId, receiver.hubSlot, ex
            )
            emitDispatchFailed(event, "publish_failed")
            return
        }

        runCatching {
            val trackingPayload = objectMapper.writeValueAsString(
                DispatchTrackingRecord(
                    deviceId = event.deviceId,
                    storeId = event.storeId,
                    ticketId = event.ticketId,
                    ticketNumber = event.ticketNumber
                )
            )
            redis.opsForValue()
                .set(
                    RedisKeyManager.dispatchTracking(dispatchId),
                    trackingPayload,
                    Duration.ofMinutes(5)
                )
                .awaitSingleOrNull()
        }.onFailure { ex ->
            log.warn("dispatch_tracking_write_failed dispatch={}", dispatchId, ex)
        }

        if (event.type == DeviceDispatchEventType.DEVICE_STOP_REQUESTED &&
            event.disposition == DeviceDispatchStopDisposition.RELEASE
        ) {
            runCatching {
                redis.delete(RedisKeyManager.deviceBusy(event.deviceId)).awaitSingleOrNull()
            }.onFailure { ex ->
                log.warn(
                    "transmitter_slot_dispatch_busy_release_failed store={} ticket={} device={}",
                    event.storeId, event.ticketId, event.deviceId, ex
                )
            }
        }
    }

    private fun buildPayload(
        hubPublicId: String,
        receiver: Device,
        decrypted: DeviceRfCodeRepository.DispatchRfCodeRecord,
        event: DeviceDispatchEvent,
        dispatchId: UUID,
        issuedAt: OffsetDateTime,
        signer: DeviceCommandSigner
    ): TransmitEnvelope {
        val patched = patchRfCode(receiver, decrypted, event)
        val rfCodeHex = patched.plaintext.toHex()
        val canonical = DeviceCanonical.transmit(
            hubPublicId = hubPublicId,
            dispatchId = dispatchId,
            receiverPublicId = requireNotNull(receiver.publicId),
            band = patched.band,
            rfCodeHex = rfCodeHex,
            rfCodeBits = patched.bits,
            protoAny = patched.protoAny,
            issuedAt = issuedAt
        )

        return TransmitEnvelope(
            dispatchId = dispatchId,
            receiverPublicId = requireNotNull(receiver.publicId),
            band = patched.band,
            rfCodeHex = rfCodeHex,
            rfCodeBits = patched.bits,
            protoAny = patched.protoAny,
            issuedAt = issuedAt.toInstant().toString(),
            signatureB64 = signer.sign(canonical)
        )
    }

    private fun patchRfCode(
        receiver: Device,
        decrypted: DeviceRfCodeRepository.DispatchRfCodeRecord,
        event: DeviceDispatchEvent
    ): DispatchPayload {
        if (!receiver.kind.isPassive()) {
            return DispatchPayload(
                plaintext = decrypted.plaintext,
                bits = decrypted.bits,
                band = if (receiver.kind.is24Band()) "2_4G" else "433M",
                protoAny = !receiver.kind.isPassive()
            )
        }

        val channel = when (event.type) {
            DeviceDispatchEventType.DEVICE_CALL_REQUESTED -> properties.rfCode.pt2272ToggleChannel
            DeviceDispatchEventType.DEVICE_STOP_REQUESTED -> properties.rfCode.pt2272DetoggleChannel
        }.toByte()

        return DispatchPayload(
            plaintext = decrypted.plaintext + byteArrayOf(channel),
            bits = 24,
            band = "433M",
            protoAny = false
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }
}

private data class DispatchPayload(
    val plaintext: ByteArray,
    val bits: Int,
    val band: String,
    val protoAny: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DispatchPayload

        if (bits != other.bits) return false
        if (protoAny != other.protoAny) return false
        if (!plaintext.contentEquals(other.plaintext)) return false
        if (band != other.band) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bits
        result = 31 * result + protoAny.hashCode()
        result = 31 * result + plaintext.contentHashCode()
        result = 31 * result + band.hashCode()
        return result
    }
}

private data class TransmitEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    @field:JsonProperty("dispatch_id")
    val dispatchId: UUID,
    @field:JsonProperty("receiver_public_id")
    val receiverPublicId: String,
    val band: String,
    @field:JsonProperty("rf_code_hex")
    val rfCodeHex: String,
    @field:JsonProperty("rf_code_bits")
    val rfCodeBits: Int,
    @field:JsonProperty("proto_any")
    val protoAny: Boolean,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)

private data class SlotDispatchEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    @field:JsonProperty("dispatch_id")
    val dispatchId: UUID,
    val slot: Int,
    val action: String,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)
