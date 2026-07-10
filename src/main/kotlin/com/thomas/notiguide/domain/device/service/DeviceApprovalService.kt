package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.controller.DeviceConflictEnvelopeException
import com.thomas.notiguide.domain.device.controller.DeviceServiceUnavailableEnvelopeException
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationByDeviceRecord
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.request.ApproveDeviceRequest
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Service
class DeviceApprovalService(
    private val deviceRepository: DeviceRepository,
    private val storeRepository: StoreRepository,
    private val deviceQueryService: DeviceQueryService,
    private val objectMapper: ObjectMapper,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val mqttPublisherProvider: ObjectProvider<DeviceMqttPublisher>,
    private val deviceTransmitterProperties: DeviceTransmitterProperties,
    private val storeAccess: StoreAccessService
) {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val secureRandom = SecureRandom()

    @Transactional
    suspend fun approve(
        deviceId: UUID,
        request: ApproveDeviceRequest,
        principal: AdminPrincipal
    ): DeviceDetailDto {
        val assignedName = request.assignedName.trim()
        storeAccess.requireStoreAccess(principal, request.storeId)
        storeRepository.findById(request.storeId)
            ?: throw NotFoundException("Store", "id", request.storeId.toString())

        val publisher = requirePublisher()
        val device = deviceRepository.findById(deviceId)
            ?: throw NotFoundException("Device", "id", deviceId.toString())
        if (device.storeId != null) {
            requireDeviceAccess(principal, device)
        }
        requirePending(device)

        if (device.kind.isHub()) {
            val publicKeyDer = requireNotNull(device.publicKeyDer) { "Transmitter hubs must have a public key" }
            val count = deviceRepository.countRegisteredHubsByStoreExcludingPublicKey(
                storeId = request.storeId,
                publicKeyDer = publicKeyDer
            )
            if (count >= deviceTransmitterProperties.maxRegisteredPerStore) {
                throw DeviceConflictEnvelopeException("hub_cap_reached")
            }
        }

        val challengeLink = readActivationByDevice(device.id!!)
        val activationRecord = readActivationRecord(challengeLink.registrationNonce)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val updatedActivation = activationRecord.copy(
            nonce = generateChallengeNonce(),
            issuedAt = now,
            expiresAt = now.plus(CHALLENGE_LIFETIME),
            status = DeviceActivationStatus.ISSUED
        )

        val saved = deviceRepository.save(
            device.copy(
                assignedName = assignedName,
                storeId = request.storeId
            )
        )

        writeActivationState(saved.id!!, challengeLink.registrationNonce, updatedActivation)
        publisher.publishChallenge(
            kind = saved.kind,
            registrationNonce = challengeLink.registrationNonce,
            nonce = requireNotNull(updatedActivation.nonce),
            issuedAt = updatedActivation.issuedAt,
            expiresAt = updatedActivation.expiresAt
        )

        return deviceQueryService.getRequiredDeviceDetailById(saved.id)
    }

    @Transactional
    suspend fun reject(
        deviceId: UUID,
        principal: AdminPrincipal
    ): DeviceDetailDto {
        val publisher = requirePublisher()
        val device = deviceRepository.findById(deviceId)
            ?: throw NotFoundException("Device", "id", deviceId.toString())
        requireDeviceAccess(principal, device)
        requirePending(device)

        val challengeLink = readActivationByDevice(device.id!!)
        readActivationRecord(challengeLink.registrationNonce)

        val saved = deviceRepository.save(
            device.copy(status = DeviceStatus.REJECTED)
        )
        publisher.publishRejected(saved.kind, challengeLink.registrationNonce, "admin_rejected")
        runCatching {
            deleteActivationState(saved.id!!, challengeLink.registrationNonce)
        }.onFailure { ex ->
            log.warn("Failed to clear activation state for rejected device {}", saved.id, ex)
        }

        return deviceQueryService.getRequiredDeviceDetailById(saved.id!!)
    }

    private suspend fun readActivationByDevice(deviceId: UUID): DeviceActivationByDeviceRecord {
        val key = RedisKeyManager.deviceActivationByDevice(deviceId)
        val payload = redis.opsForValue().get(key).awaitSingleOrNull()
            ?: throw DeviceConflictEnvelopeException("activation_session_missing")
        return runCatching { objectMapper.readValue(payload, DeviceActivationByDeviceRecord::class.java) }
            .getOrElse { throw ConflictException("Device activation session is malformed") }
    }

    private suspend fun readActivationRecord(registrationNonce: String): DeviceActivationRecord {
        val key = RedisKeyManager.deviceActivation(registrationNonce)
        val payload = redis.opsForValue().get(key).awaitSingleOrNull()
            ?: throw DeviceConflictEnvelopeException("activation_session_missing")
        return runCatching { objectMapper.readValue(payload, DeviceActivationRecord::class.java) }
            .getOrElse { throw ConflictException("Device activation session is malformed") }
    }

    private suspend fun writeActivationState(
        deviceId: UUID,
        registrationNonce: String,
        activationRecord: DeviceActivationRecord
    ) {
        val activationJson = objectMapper.writeValueAsString(activationRecord)
        val activationByDeviceJson = objectMapper.writeValueAsString(
            DeviceActivationByDeviceRecord(registrationNonce = registrationNonce)
        )

        redis.opsForValue()
            .set(RedisKeyManager.deviceActivation(registrationNonce), activationJson, ACTIVATION_TTL)
            .awaitSingle()
        try {
            redis.opsForValue()
                .set(RedisKeyManager.deviceActivationByDevice(deviceId), activationByDeviceJson, ACTIVATION_TTL)
                .awaitSingle()
        } catch (ex: Exception) {
            redis.delete(RedisKeyManager.deviceActivation(registrationNonce)).awaitSingleOrNull()
            throw ex
        }
    }

    private suspend fun deleteActivationState(
        deviceId: UUID,
        registrationNonce: String
    ) {
        redis.delete(RedisKeyManager.deviceActivation(registrationNonce)).awaitSingleOrNull()
        redis.delete(RedisKeyManager.deviceActivationByDevice(deviceId)).awaitSingleOrNull()
    }

    private fun requirePublisher(): DeviceMqttPublisher =
        mqttPublisherProvider.ifAvailable
            ?: throw DeviceServiceUnavailableEnvelopeException(
                error = "device_mqtt_unavailable",
                detailMessage = "Device MQTT publishing is unavailable"
            )

    private fun requirePending(device: Device) {
        if (device.status != DeviceStatus.PENDING) {
            throw DeviceConflictEnvelopeException("device_not_pending")
        }
    }

    private suspend fun requireDeviceAccess(
        principal: AdminPrincipal,
        device: Device
    ) {
        val storeId = device.storeId
        if (storeId == null) {
            // A storeless/pending device has no org fence yet: only SUPER_ADMIN may touch it.
            if (isSuperAdmin(principal)) return
            throw ForbiddenException("Store-scoped admins need an assigned store to access this device")
        }
        // Store-assigned device: both roles go through the org-aware fence.
        storeAccess.requireStoreAccess(principal, storeId)
    }

    private fun generateChallengeNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }

    companion object {
        private val ACTIVATION_TTL: Duration = Duration.ofMinutes(15)
        private val CHALLENGE_LIFETIME: Duration = Duration.ofMinutes(5)
    }
}
