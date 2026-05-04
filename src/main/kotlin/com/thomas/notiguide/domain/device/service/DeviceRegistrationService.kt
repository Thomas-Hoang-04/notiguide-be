package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationByDeviceRecord
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.types.DeviceFamily
import com.thomas.notiguide.domain.device.types.DeviceHardwareModel
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Service
@ConditionalOnBean(MqttClientManager::class)
class DeviceRegistrationService(
    private val objectMapper: ObjectMapper,
    private val enrollmentTokenService: EnrollmentTokenService,
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val deviceMqttPublisher: DeviceMqttPublisher,
    private val deviceTransmitterProperties: DeviceTransmitterProperties,
    private val redis: ReactiveRedisTemplate<String, String>
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Transactional
    suspend fun onRegister(
        payload: String,
        family: DeviceFamily
    ) {
        val challengeId = UUID.randomUUID()

        val registration = when (family) {
            DeviceFamily.RECEIVER -> parseReceiverRegistration(payload, challengeId)
            DeviceFamily.TRANSMITTER -> parseTransmitterRegistration(payload, challengeId)
        } ?: return

        val enrollmentRecord = enrollmentTokenService.consume(registration.enrollmentToken)
        if (enrollmentRecord == null) {
            deviceMqttPublisher.publishRejected(family, challengeId, "invalid_token")
            return
        }

        if (family == DeviceFamily.TRANSMITTER && enrollmentRecord.storeId != null) {
            val count = deviceRepository.countRegisteredHubsByStoreExcludingPublicKey(
                storeId = enrollmentRecord.storeId,
                publicKeyDer = registration.publicKeyDer
            )
            if (count >= deviceTransmitterProperties.maxRegisteredPerStore) {
                deviceMqttPublisher.publishRejected(family, challengeId, "hub_cap_reached")
                return
            }
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expiresAt = now.plus(Duration.ofMinutes(15))
        val existing = deviceRepository.findByPublicKeyDer(registration.publicKeyDer)
        val saved = deviceRepository.save(
            Device(
                id = existing?.id,
                publicId = null,
                publicKeyDer = registration.publicKeyDer,
                hardwareModel = registration.hardwareModel,
                kind = registration.kind,
                status = DeviceStatus.PENDING,
                assignedName = existing?.assignedName,
                storeId = if (family == DeviceFamily.TRANSMITTER) {
                    enrollmentRecord.storeId ?: existing?.storeId
                } else {
                    existing?.storeId
                },
                firmwareVersion = registration.firmwareVersion,
                lastSeenAt = null,
                activatedAt = null,
                createdAt = existing?.createdAt
            )
        )

        if (existing != null) {
            deviceRfCodeRepository.deleteByDeviceId(saved.id!!)
        }

        val activationRecord = DeviceActivationRecord(
            deviceId = saved.id!!,
            publicKeyFingerprint = sha256Hex(registration.publicKeyDer),
            registrationNonce = registration.registrationNonce,
            nonce = null,
            issuedAt = now,
            expiresAt = expiresAt,
            status = DeviceActivationStatus.PENDING
        )
        val activationByDevice = DeviceActivationByDeviceRecord(challengeId = challengeId)
        val activationJson = objectMapper.writeValueAsString(activationRecord)
        val activationByDeviceJson = objectMapper.writeValueAsString(activationByDevice)

        writeActivationKeys(saved.id, challengeId, activationJson, activationByDeviceJson)
        deviceMqttPublisher.publishPending(
            family = family,
            challengeId = challengeId,
            registrationNonce = registration.registrationNonce,
            issuedAt = now
        )
    }

    private suspend fun parseReceiverRegistration(
        payload: String,
        challengeId: UUID
    ): ParsedRegistration? {
        val request = runCatching { objectMapper.readValue(payload, ReceiverBootstrapRegistration::class.java) }
            .getOrElse {
                log.warn("Ignoring malformed receiver registration payload", it)
                return null
            }

        if (request.schemaVersion != 1 || request.firmwareVersion.isBlank() || !isValidRegistrationNonce(request.registrationNonce)) {
            log.warn("Ignoring invalid receiver registration payload: schemaVersion={} firmwareBlank={} nonceValid={}",
                request.schemaVersion, request.firmwareVersion.isBlank(), isValidRegistrationNonce(request.registrationNonce))
            return null
        }

        val hardwareModel = runCatching { DeviceHardwareModel.fromWireValue(request.hardwareModel) }.getOrNull()
        val kind = runCatching { DeviceKind.valueOf(request.receiverType) }.getOrNull()
        if (hardwareModel == null || kind == null || kind == DeviceKind.TRANSMITTER_HUB || kind == DeviceKind.RECEIVER_433M_PASSIVE) {
            deviceMqttPublisher.publishRejected(DeviceFamily.RECEIVER, challengeId, "model_radio_mismatch")
            return null
        }
        if (!isLegalHardwarePair(hardwareModel, kind)) {
            deviceMqttPublisher.publishRejected(DeviceFamily.RECEIVER, challengeId, "model_radio_mismatch")
            return null
        }

        val publicKeyDer = decodePublicKeyDer(request.publicKeyB64) ?: return null
        return ParsedRegistration(
            hardwareModel = hardwareModel,
            kind = kind,
            firmwareVersion = request.firmwareVersion.trim(),
            publicKeyDer = publicKeyDer,
            enrollmentToken = request.enrollmentToken,
            registrationNonce = request.registrationNonce
        )
    }

    private suspend fun parseTransmitterRegistration(
        payload: String,
        challengeId: UUID
    ): ParsedRegistration? {
        val request = runCatching { objectMapper.readValue(payload, TransmitterBootstrapRegistration::class.java) }
            .getOrElse {
                log.warn("Ignoring malformed transmitter registration payload", it)
                return null
            }

        if (request.schemaVersion != 1 || request.firmwareVersion.isBlank() || !isValidRegistrationNonce(request.registrationNonce)) {
            log.warn("Ignoring invalid transmitter registration payload: schemaVersion={} firmwareBlank={} nonceValid={}",
                request.schemaVersion, request.firmwareVersion.isBlank(), isValidRegistrationNonce(request.registrationNonce))
            return null
        }

        val hardwareModel = runCatching { DeviceHardwareModel.fromWireValue(request.hardwareModel) }.getOrNull()
        val kind = runCatching { DeviceKind.valueOf(request.kind) }.getOrNull()
        if (hardwareModel == null || kind != DeviceKind.TRANSMITTER_HUB || !isLegalHardwarePair(hardwareModel, DeviceKind.TRANSMITTER_HUB)) {
            deviceMqttPublisher.publishRejected(DeviceFamily.TRANSMITTER, challengeId, "model_radio_mismatch")
            return null
        }

        val publicKeyDer = decodePublicKeyDer(request.publicKeyB64) ?: return null
        return ParsedRegistration(
            hardwareModel = hardwareModel,
            kind = DeviceKind.TRANSMITTER_HUB,
            firmwareVersion = request.firmwareVersion.trim(),
            publicKeyDer = publicKeyDer,
            enrollmentToken = request.enrollmentToken,
            registrationNonce = request.registrationNonce
        )
    }

    private suspend fun writeActivationKeys(
        deviceId: UUID,
        challengeId: UUID,
        activationJson: String,
        activationByDeviceJson: String
    ) {
        val ttl = Duration.ofMinutes(15)
        val activationKey = RedisKeyManager.deviceActivation(challengeId)
        val activationByDeviceKey = RedisKeyManager.deviceActivationByDevice(deviceId)

        redis.opsForValue()
            .set(activationKey, activationJson, ttl)
            .awaitSingle()

        try {
            redis.opsForValue()
                .set(activationByDeviceKey, activationByDeviceJson, ttl)
                .awaitSingle()
        } catch (ex: Exception) {
            runCatching {
                redis.delete(activationKey).awaitSingleOrNull()
            }
            throw ex
        }
    }

    private fun decodePublicKeyDer(publicKeyB64: String): ByteArray? {
        val decoded = runCatching { Base64.getDecoder().decode(publicKeyB64) }
            .getOrElse {
                log.warn("Ignoring registration payload with invalid base64 public key", it)
                return null
            }
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoded))
        }.getOrElse {
            log.warn("Ignoring registration payload with invalid DER public key", it)
            return null
        }
        val ecPublicKey = publicKey as? ECPublicKey
        if (ecPublicKey == null || ecPublicKey.params.curve.field.fieldSize != 256) {
            log.warn("Ignoring registration payload with non-P-256 public key")
            return null
        }
        return decoded
    }

    private fun isValidRegistrationNonce(value: String): Boolean {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return false
        return decoded.size >= 8
    }

    private fun isLegalHardwarePair(
        hardwareModel: DeviceHardwareModel,
        kind: DeviceKind
    ): Boolean = when (hardwareModel) {
        DeviceHardwareModel.ESP_01 -> kind == DeviceKind.RECEIVER_433M
        DeviceHardwareModel.ESP32_C3 -> kind in setOf(
            DeviceKind.RECEIVER_433M,
            DeviceKind.RECEIVER_2_4G,
            DeviceKind.TRANSMITTER_HUB
        )
        DeviceHardwareModel.PT2272 -> kind == DeviceKind.RECEIVER_433M_PASSIVE
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

private data class ParsedRegistration(
    val hardwareModel: DeviceHardwareModel,
    val kind: DeviceKind,
    val firmwareVersion: String,
    val publicKeyDer: ByteArray,
    val enrollmentToken: String,
    val registrationNonce: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedRegistration

        if (hardwareModel != other.hardwareModel) return false
        if (kind != other.kind) return false
        if (firmwareVersion != other.firmwareVersion) return false
        if (!publicKeyDer.contentEquals(other.publicKeyDer)) return false
        if (enrollmentToken != other.enrollmentToken) return false
        if (registrationNonce != other.registrationNonce) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hardwareModel.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + publicKeyDer.contentHashCode()
        result = 31 * result + enrollmentToken.hashCode()
        result = 31 * result + registrationNonce.hashCode()
        return result
    }
}

private data class ReceiverBootstrapRegistration(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("hardware_model")
    val hardwareModel: String = "",
    @field:JsonProperty("receiver_type")
    val receiverType: String = "",
    @field:JsonProperty("firmware_version")
    val firmwareVersion: String = "",
    @field:JsonProperty("public_key_b64")
    val publicKeyB64: String = "",
    @field:JsonProperty("enrollment_token")
    val enrollmentToken: String = "",
    @field:JsonProperty("registration_nonce")
    val registrationNonce: String = ""
)

private data class TransmitterBootstrapRegistration(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("hardware_model")
    val hardwareModel: String = "",
    val kind: String = "",
    @field:JsonProperty("firmware_version")
    val firmwareVersion: String = "",
    @field:JsonProperty("public_key_b64")
    val publicKeyB64: String = "",
    @field:JsonProperty("enrollment_token")
    val enrollmentToken: String = "",
    @field:JsonProperty("registration_nonce")
    val registrationNonce: String = ""
)
