package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceCommandSigner
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.controller.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.controller.DeviceConflictEnvelopeException
import com.thomas.notiguide.domain.device.controller.DeviceServiceUnavailableEnvelopeException
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceLifecycleCommandRecord
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.request.DeviceLifecycleRequest
import com.thomas.notiguide.domain.device.types.DeviceLifecycleAckStatus
import com.thomas.notiguide.domain.device.types.DeviceLifecycleAction
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class DeviceLifecycleService(
    private val objectMapper: ObjectMapper,
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val deviceQueryService: DeviceQueryService,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val signerProvider: ObjectProvider<DeviceCommandSigner>,
    private val publisherProvider: ObjectProvider<DeviceMqttPublisher>
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Transactional
    suspend fun issue(
        deviceId: UUID,
        request: DeviceLifecycleRequest,
        principal: AdminPrincipal
    ): DeviceDetailDto {
        val action = DeviceLifecycleAction.fromWireValue(request.action)
            ?: throw DeviceBadRequestEnvelopeException("invalid_lifecycle_action")
        val device = loadAccessibleDevice(deviceId, principal)

        if (device.status == DeviceStatus.DECOMMISSIONED) {
            throw DeviceConflictEnvelopeException("device_decommissioned")
        }

        if (device.kind.isPassive()) {
            val saved = deviceRepository.save(device.copy(status = action.targetStatus()))
            redis.delete(RedisKeyManager.deviceLifecycleCommand(saved.id!!)).awaitSingleOrNull()
            return deviceQueryService.getRequiredDeviceDetailById(saved.id)
        }

        val publicId = device.publicId ?: throw DeviceConflictEnvelopeException("device_not_active")
        val signer = requireSigner()
        val publisher = requirePublisher()
        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val commandId = UUID.randomUUID()

        val payload = LifecycleCommandEnvelope(
            action = action.wireValue,
            commandId = commandId,
            issuedAt = issuedAt.toInstant().toString(),
            signatureB64 = signer.sign(
                DeviceCanonical.deact(
                    publicId = publicId,
                    commandId = commandId,
                    action = action.wireValue,
                    issuedAt = issuedAt
                )
            )
        )

        val lifecycleRecord = DeviceLifecycleCommandRecord(
            commandId = commandId,
            action = action.wireValue,
            issuedAt = issuedAt,
            ackStatus = DeviceLifecycleAckStatus.PENDING
        )
        val lifecycleKey = RedisKeyManager.deviceLifecycleCommand(device.id!!)
        redis.opsForValue()
            .set(
                lifecycleKey,
                objectMapper.writeValueAsString(lifecycleRecord),
                LIFECYCLE_TTL
            )
            .awaitSingle()

        try {
            publisher.publishDeact(publicId, device.kind, payload)
        } catch (ex: Exception) {
            runCatching {
                redis.delete(lifecycleKey).awaitSingleOrNull()
            }.onFailure { cleanupEx ->
                log.warn("Failed to clear lifecycle command after publish failure for {}", publicId, cleanupEx)
            }
            throw ex
        }
        return deviceQueryService.getRequiredDeviceDetailById(device.id)
    }

    @Transactional
    suspend fun reprovision(
        deviceId: UUID,
        principal: AdminPrincipal
    ): DeviceDetailDto {
        val device = loadAccessibleDevice(deviceId, principal)
        if (device.kind.isPassive()) {
            throw NotFoundException("Device", "id", deviceId.toString())
        }
        val priorPublicId = device.publicId

        val saved = deviceRepository.save(
            device.copy(
                publicId = null,
                status = DeviceStatus.PENDING,
                activatedAt = null
            )
        )

        if (!saved.kind.isHub()) {
            deviceRfCodeRepository.deleteByDeviceId(saved.id!!)
        }
        if (saved.kind.isHub()) {
            saved.storeId?.let { redis.delete(RedisKeyManager.storeTransmitterActive(it)).awaitSingleOrNull() }
        }
        redis.delete(RedisKeyManager.deviceLifecycleCommand(saved.id!!)).awaitSingleOrNull()
        if (priorPublicId != null) {
            redis.opsForValue()
                .set(RedisKeyManager.devicePriorPublicId(saved.id), priorPublicId)
                .awaitSingle()
        }

        return deviceQueryService.getRequiredDeviceDetailById(saved.id)
    }

    @Transactional
    suspend fun onAck(
        publicId: String,
        payload: String
    ) {
        val ack = runCatching {
            objectMapper.readValue(payload, LifecycleAckEnvelope::class.java)
        }.getOrElse {
            log.warn("Ignoring malformed lifecycle ack for {}", publicId, it)
            return
        }

        if (ack.schemaVersion != 1 || ack.ackFor != "deact") {
            return
        }

        val action = DeviceLifecycleAction.fromWireValue(ack.action) ?: return
        val status = DeviceLifecycleAckStatus.fromWireValue(ack.status) ?: return
        val ackAt = ack.appliedAt ?: OffsetDateTime.now(ZoneOffset.UTC)
        val device = deviceRepository.findByPublicId(publicId) ?: return
        val key = RedisKeyManager.deviceLifecycleCommand(device.id!!)
        val recordJson = redis.opsForValue().get(key).awaitSingleOrNull()

        if (recordJson == null) {
            deviceRepository.save(device.copy(lastSeenAt = ackAt))
            log.warn("Received lifecycle ack without an active command for {}", publicId)
            return
        }

        val record = runCatching {
            objectMapper.readValue(recordJson, DeviceLifecycleCommandRecord::class.java)
        }.getOrElse {
            deviceRepository.save(device.copy(lastSeenAt = ackAt))
            log.warn("Ignoring malformed lifecycle command record for {}", publicId, it)
            return
        }

        if (record.commandId != ack.commandId || record.action != action.wireValue) {
            deviceRepository.save(device.copy(lastSeenAt = ackAt))
            log.warn("Ignoring out-of-order lifecycle ack for {}", publicId)
            return
        }

        when (status) {
            DeviceLifecycleAckStatus.OK -> handleSuccessfulAck(device, action, ackAt, key)
            DeviceLifecycleAckStatus.IGNORED,
            DeviceLifecycleAckStatus.REJECTED -> {
                deviceRepository.save(device.copy(lastSeenAt = ackAt))
                val updated = record.copy(ackStatus = status)
                redis.opsForValue().set(key, objectMapper.writeValueAsString(updated), LIFECYCLE_TTL).awaitSingle()
            }
            DeviceLifecycleAckStatus.PENDING -> Unit
        }
    }

    private suspend fun handleSuccessfulAck(
        device: Device,
        action: DeviceLifecycleAction,
        ackAt: OffsetDateTime,
        lifecycleKey: String
    ) {
        val saved = deviceRepository.save(
            device.copy(
                status = action.targetStatus(),
                lastSeenAt = ackAt
            )
        )
        redis.delete(lifecycleKey).awaitSingleOrNull()

        if (action == DeviceLifecycleAction.DECOMMISSION) {
            publisherProvider.ifAvailable?.clearRetained(
                publicId = requireNotNull(device.publicId),
                kind = device.kind
            )
        }

        if (saved.kind.isHub() &&
            action in setOf(DeviceLifecycleAction.SUSPEND, DeviceLifecycleAction.DECOMMISSION)
        ) {
            saved.storeId?.let { redis.delete(RedisKeyManager.storeTransmitterActive(it)).awaitSingleOrNull() }
        }
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
        publisherProvider.ifAvailable
            ?: throw DeviceServiceUnavailableEnvelopeException(
                error = "device_mqtt_unavailable",
                detailMessage = "Device MQTT publishing is unavailable"
            )

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }

    private fun DeviceLifecycleAction.targetStatus(): DeviceStatus = when (this) {
        DeviceLifecycleAction.SUSPEND -> DeviceStatus.SUSPENDED
        DeviceLifecycleAction.RESUME -> DeviceStatus.ACTIVE
        DeviceLifecycleAction.DECOMMISSION -> DeviceStatus.DECOMMISSIONED
    }

    companion object {
        private val LIFECYCLE_TTL: Duration = Duration.ofMinutes(30)
    }
}

private data class LifecycleCommandEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    val action: String,
    @field:JsonProperty("command_id")
    val commandId: UUID,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)

private data class LifecycleAckEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 0,
    @field:JsonProperty("ack_for")
    val ackFor: String = "",
    @field:JsonProperty("command_id")
    val commandId: UUID = UUID(0L, 0L),
    val action: String = "",
    val status: String = "",
    @field:JsonProperty("applied_at")
    val appliedAt: OffsetDateTime? = null
)
