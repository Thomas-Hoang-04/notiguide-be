package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceCommandSigner
import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.exception.ServiceUnavailableException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.request.RotateRfCodeRequest
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class RfCodeService(
    private val objectMapper: ObjectMapper,
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val deviceQueryService: DeviceQueryService,
    private val rfCodeValidator: RfCodeValidator,
    private val rfCodeForbiddenSet: RfCodeForbiddenSet,
    private val properties: DeviceCommandSigningProperties,
    private val signerProvider: ObjectProvider<DeviceCommandSigner>,
    private val mqttPublisherProvider: ObjectProvider<DeviceMqttPublisher>
) {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val secureRandom = SecureRandom()

    @Transactional
    suspend fun rotate(
        deviceId: UUID,
        request: RotateRfCodeRequest,
        principal: AdminPrincipal
    ): DeviceDetailDto {
        val device = loadAccessibleDevice(deviceId, principal)
        when {
            device.kind.isPassive() -> throw DeviceBadRequestEnvelopeException(
                error = "hardware_fixed_code",
                detailMessage = "PT2272 addresses are set at the chip and cannot rotate. Replace the device or update the chip's DIP switches and re-register."
            )
            device.kind.isHub() -> throw DeviceBadRequestEnvelopeException("hub_no_rf_code")
        }
        if (device.status !in setOf(DeviceStatus.PENDING_RF_CODE, DeviceStatus.ACTIVE, DeviceStatus.SUSPENDED)) {
            throw DeviceConflictEnvelopeException("rf_code_unavailable")
        }

        val publicId = device.publicId ?: throw DeviceConflictEnvelopeException("device_not_active")
        val signer = requireSigner()
        val publisher = requirePublisher()
        requireEncryptionAvailable()

        val bits = resolveRequestedBits(device.kind, request.rfCodeBits)
        val plaintext = generateUniquePlaintext(device, bits)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val current = deviceRfCodeRepository.findCurrent(deviceId)
        val nextVersion = if (current == null) 1 else current.version + 1

        if (current == null) {
            deviceRfCodeRepository.insert(
                deviceId = device.id!!,
                plaintext = plaintext,
                bits = bits,
                version = nextVersion,
                issuedAt = now,
                ack = DeviceRfAckStatus.PENDING,
                ackAt = null
            )
        } else {
            deviceRfCodeRepository.update(
                deviceId = device.id!!,
                plaintext = plaintext,
                bits = bits,
                version = nextVersion,
                issuedAt = now,
                ack = DeviceRfAckStatus.PENDING,
                ackAt = null
            )
        }

        publishRfCode(
            publicId = publicId,
            version = nextVersion,
            plaintext = plaintext,
            bits = bits,
            issuedAt = now,
            signer = signer,
            publisher = publisher
        )

        return deviceQueryService.getRequiredDeviceDetailById(device.id)
    }

    @Transactional
    suspend fun autoIssue(deviceId: UUID) {
        val device = deviceRepository.findById(deviceId) ?: return
        if (device.kind.isPassive() || device.kind.isHub()) {
            return
        }
        val publicId = device.publicId ?: return
        val bits = if (device.kind.is24Band()) 40 else properties.rfCode.defaultBits433m
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val plaintext = try {
            generateUniquePlaintext(device, bits)
        } catch (ex: Exception) {
            log.warn("Auto-issue failed for device {}", deviceId, ex)
            return
        }

        try {
            deviceRfCodeRepository.deleteByDeviceId(device.id!!)
            deviceRfCodeRepository.insert(
                deviceId = device.id,
                plaintext = plaintext,
                bits = bits,
                version = 1,
                issuedAt = now,
                ack = DeviceRfAckStatus.PENDING,
                ackAt = null
            )
        } catch (_: ServiceUnavailableException) {
            log.warn("RF-code encryption unavailable for auto-issue on device {}", deviceId)
            return
        }

        val signer = signerProvider.ifAvailable
        val publisher = mqttPublisherProvider.ifAvailable
        if (signer == null || publisher == null) {
            log.info("Deferred RF-code publish for device {} because signing or MQTT is unavailable", deviceId)
            return
        }

        publishRfCode(
            publicId = publicId,
            version = 1,
            plaintext = plaintext,
            bits = bits,
            issuedAt = now,
            signer = signer,
            publisher = publisher
        )
    }

    @Transactional
    suspend fun onAck(
        publicId: String,
        payload: String
    ) {
        val ack = runCatching {
            objectMapper.readValue(payload, ReceiverRfCodeAckEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed RF-code ack for {}", publicId, it)
            return
        }

        if (ack.schemaVersion != 1 || ack.ackFor != "rf_code") {
            return
        }

        val device = deviceRepository.findByPublicId(publicId) ?: return
        val ackStatus = DeviceRfAckStatus.entries.firstOrNull {
            it.name == ack.status.trim().uppercase()
        } ?: return

        val ackAt = ack.appliedAt ?: OffsetDateTime.now(ZoneOffset.UTC)
        val updated = deviceRfCodeRepository.updateAckIfVersion(
            deviceId = device.id!!,
            version = ack.codeVersion,
            ack = ackStatus,
            ackAt = ackAt
        )

        val nextStatus = if (updated && device.status == DeviceStatus.PENDING_RF_CODE &&
            ackStatus in setOf(DeviceRfAckStatus.APPLIED, DeviceRfAckStatus.UNCHANGED)
        ) {
            DeviceStatus.ACTIVE
        } else {
            device.status
        }

        deviceRepository.save(device.copy(status = nextStatus, lastSeenAt = ackAt))
    }

    private suspend fun loadAccessibleDevice(
        deviceId: UUID,
        principal: AdminPrincipal
    ): Device {
        val device = deviceRepository.findById(deviceId)
            ?: throw NotFoundException("Device", "id", deviceId.toString())
        if (isSuperAdmin(principal)) {
            return device
        }
        val storeId = device.storeId
            ?: throw ForbiddenException("Store-scoped admins need an assigned store to access this device")
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return device
    }

    private fun requireSigner(): DeviceCommandSigner =
        signerProvider.ifAvailable
            ?: throw DeviceServiceUnavailableEnvelopeException(
                error = "device_command_signing_unavailable",
                detailMessage = "Device command signing is unavailable"
            )

    private fun requirePublisher(): DeviceMqttPublisher =
        mqttPublisherProvider.ifAvailable
            ?: throw DeviceServiceUnavailableEnvelopeException(
                error = "device_mqtt_unavailable",
                detailMessage = "Device MQTT publishing is unavailable"
            )

    private fun requireEncryptionAvailable() {
        if (properties.rfCode.encryptionKey.trim().isEmpty()) {
            throw DeviceServiceUnavailableEnvelopeException(
                error = "rf_code_encryption_unavailable",
                detailMessage = "Device RF-code encryption is unavailable"
            )
        }
    }

    private fun resolveRequestedBits(
        kind: DeviceKind,
        requestedBits: Int?
    ): Int = when {
        kind.is24Band() -> {
            if (requestedBits != null && requestedBits != 40) {
                throw DeviceBadRequestEnvelopeException("width_out_of_range")
            }
            40
        }
        else -> requestedBits ?: throw DeviceBadRequestEnvelopeException("width_out_of_range")
    }

    private suspend fun generateUniquePlaintext(
        device: Device,
        bits: Int
    ): ByteArray {
        if (device.kind.is433Band() && device.storeId == null) {
            throw DeviceConflictEnvelopeException("device_store_unassigned")
        }

        repeat(MAX_GENERATION_ATTEMPTS) {
            val plaintext = generatePlaintext(device.kind, bits)
            runCatching {
                rfCodeValidator.validate(device.kind, bits, plaintext)
                rfCodeForbiddenSet.assertAllowed(device.kind, bits, plaintext)
            }.getOrElse {
                return@repeat
            }

            deviceRfCodeRepository.findCollision(device.kind, device.storeId, plaintext) ?: return plaintext
        }

        throw DeviceConflictEnvelopeException("rf_code_space_exhausted")
    }

    private fun generatePlaintext(
        kind: DeviceKind,
        bits: Int
    ): ByteArray {
        val byteLen = (bits + 7) / 8
        val plaintext = ByteArray(byteLen)
        secureRandom.nextBytes(plaintext)

        if (kind.is24Band()) {
            return plaintext
        }

        val unusedBits = (byteLen * 8) - bits
        if (unusedBits > 0) {
            val mask = 0xFF ushr unusedBits
            plaintext[0] = (plaintext[0].toInt() and mask).toByte()
        }
        return plaintext
    }

    private suspend fun publishRfCode(
        publicId: String,
        version: Int,
        plaintext: ByteArray,
        bits: Int,
        issuedAt: OffsetDateTime,
        signer: DeviceCommandSigner,
        publisher: DeviceMqttPublisher
    ) {
        val rfCodeHex = plaintext.toHex()
        val canonical = DeviceCanonical.rfCode(
            publicId = publicId,
            codeVersion = version,
            rfCodeHex = rfCodeHex,
            rfCodeBits = bits,
            issuedAt = issuedAt
        )
        val payload = RfCodeEnvelope(
            rfCodeHex = rfCodeHex,
            rfCodeBits = bits,
            codeVersion = version,
            issuedAt = issuedAt.toInstant().toString(),
            signatureB64 = signer.sign(canonical)
        )
        publisher.publishRfCode(publicId, payload)
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }

    companion object {
        private const val MAX_GENERATION_ATTEMPTS = 8
    }
}

private data class RfCodeEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    @field:JsonProperty("rf_code_hex")
    val rfCodeHex: String,
    @field:JsonProperty("rf_code_bits")
    val rfCodeBits: Int,
    @field:JsonProperty("code_version")
    val codeVersion: Int,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)

private data class ReceiverRfCodeAckEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("ack_for")
    val ackFor: String = "",
    @field:JsonProperty("code_version")
    val codeVersion: Int = 0,
    val status: String = "",
    @field:JsonProperty("applied_at")
    val appliedAt: OffsetDateTime? = null
)
