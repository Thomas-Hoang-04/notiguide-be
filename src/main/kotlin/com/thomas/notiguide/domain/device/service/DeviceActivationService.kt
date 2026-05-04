package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DevicePublicIdMinter
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceStatus
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Service
@ConditionalOnBean(MqttClientManager::class)
class DeviceActivationService(
    private val objectMapper: ObjectMapper,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val deviceRepository: DeviceRepository,
    private val deviceMqttPublisher: DeviceMqttPublisher,
    private val devicePublicIdMinter: DevicePublicIdMinter,
    private val rfCodeService: RfCodeService
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Transactional
    suspend fun onResponse(
        payload: String,
        challengeId: UUID
    ) {
        val response = runCatching {
            objectMapper.readValue(payload, ActivationResponseEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed device activation response for challenge {}", challengeId, it)
            return
        }

        if (response.schemaVersion != 1 || response.type != "response" || response.challengeId != challengeId) {
            return
        }

        val activationRecord = loadActivationRecord(challengeId) ?: return
        if (activationRecord.status != DeviceActivationStatus.ISSUED) {
            return
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        if (activationRecord.expiresAt.isBefore(now)) {
            return
        }

        val device = deviceRepository.findById(activationRecord.deviceId) ?: return
        if (device.status != DeviceStatus.PENDING) {
            return
        }
        if (!verifyResponse(device, challengeId, activationRecord, response.signatureB64)) {
            log.warn("Ignoring activation response with invalid signature for device {}", device.id)
            return
        }

        val priorPublicId = device.publicId ?: loadPriorPublicId(device.id!!)
        val newPublicId = devicePublicIdMinter.mint(device.kind)
        val nextStatus = if (device.kind.isHub()) DeviceStatus.ACTIVE else DeviceStatus.PENDING_RF_CODE
        val saved = deviceRepository.save(
            device.copy(
                publicId = newPublicId,
                status = nextStatus,
                activatedAt = now
            )
        )

        runCatching {
            deleteActivationState(saved.id!!, challengeId)
        }.onFailure { ex ->
            log.warn("Failed to clear activation state for device {}", saved.id, ex)
        }

        deviceMqttPublisher.publishResult(
            kind = saved.kind,
            challengeId = challengeId,
            publicId = newPublicId,
            assignedDeviceName = requireNotNull(saved.assignedName) {
                "Approved devices must have an assigned name before activation"
            }
        )

        if (priorPublicId != null) {
            runCatching {
                deletePriorPublicId(saved.id!!)
            }.onFailure { ex ->
                log.warn("Failed to clear stored prior public id for device {}", saved.id, ex)
            }

            runCatching {
                deviceMqttPublisher.clearRetained(priorPublicId, saved.kind)
            }.onFailure { ex ->
                log.warn("Failed to clear retained topics for device {}", saved.id, ex)
            }
        }

        if (!saved.kind.isHub()) {
            runCatching {
                rfCodeService.autoIssue(saved.id!!)
            }.onFailure { ex ->
                log.warn("Initial RF-code issue failed for device {}", saved.id, ex)
            }
        }
    }

    private suspend fun loadActivationRecord(challengeId: UUID): DeviceActivationRecord? {
        val payload = redis.opsForValue()
            .get(RedisKeyManager.deviceActivation(challengeId))
            .awaitSingleOrNull()
            ?: return null
        return runCatching { objectMapper.readValue(payload, DeviceActivationRecord::class.java) }
            .getOrNull()
    }

    private suspend fun deleteActivationState(
        deviceId: UUID,
        challengeId: UUID
    ) {
        redis.delete(RedisKeyManager.deviceActivation(challengeId)).awaitSingleOrNull()
        redis.delete(RedisKeyManager.deviceActivationByDevice(deviceId)).awaitSingleOrNull()
    }

    private suspend fun loadPriorPublicId(deviceId: UUID): String? =
        redis.opsForValue()
            .get(RedisKeyManager.devicePriorPublicId(deviceId))
            .awaitSingleOrNull()

    private suspend fun deletePriorPublicId(deviceId: UUID) {
        redis.delete(RedisKeyManager.devicePriorPublicId(deviceId)).awaitSingleOrNull()
    }

    private fun verifyResponse(
        device: Device,
        challengeId: UUID,
        activationRecord: DeviceActivationRecord,
        signatureB64: String
    ): Boolean {
        val publicKeyDer = device.publicKeyDer ?: return false
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        }.getOrNull() as? ECPublicKey ?: return false
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureB64) }.getOrNull() ?: return false
        val canonical = DeviceCanonical.activate(
            challengeId = challengeId,
            nonce = activationRecord.nonce ?: return false,
            issuedAt = activationRecord.issuedAt,
            expiresAt = activationRecord.expiresAt
        )

        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            verify(signatureBytes)
        }
    }
}

private data class ActivationResponseEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    val type: String = "",
    @field:JsonProperty("challenge_id")
    val challengeId: UUID = UUID(0L, 0L),
    @field:JsonProperty("signature_b64")
    val signatureB64: String = ""
)
