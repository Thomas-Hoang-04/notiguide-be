package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.core.device.DevicePublicIdMinter
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.device.controller.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.controller.PassiveDeviceConflictException
import com.thomas.notiguide.domain.device.dto.DeviceDto
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.request.PassiveDeviceRegistrationRequest
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class PassiveDeviceRegistrationService(
    private val storeRepository: StoreRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val deviceQueryService: DeviceQueryService,
    private val devicePublicIdMinter: DevicePublicIdMinter,
    private val rfCodeValidator: RfCodeValidator,
    private val rfCodeForbiddenSet: RfCodeForbiddenSet,
    private val storeAccess: StoreAccessService
) {

    @Transactional
    suspend fun register(
        request: PassiveDeviceRegistrationRequest,
        principal: AdminPrincipal
    ): DeviceDto {
        storeAccess.requireStoreAccess(principal, request.storeId)
        storeRepository.findById(request.storeId)
            ?: throw NotFoundException("Store", "id", request.storeId.toString())

        if (request.kind != DeviceKind.RECEIVER_433M_PASSIVE) {
            throw IllegalArgumentException("Passive device registration only supports RECEIVER_433M_PASSIVE")
        }

        if (request.rfCodeBits != 16) {
            throw DeviceBadRequestEnvelopeException(
                error = "pt2272_address_width_mismatch",
                required = 16
            )
        }

        val normalizedHex = request.rfCodeHex.trim().uppercase()
        if (!HEX_REGEX.matches(normalizedHex)) {
            throw IllegalArgumentException("rfCodeHex must be valid hexadecimal")
        }
        if (normalizedHex.length != 4) {
            throw DeviceBadRequestEnvelopeException("width_out_of_range")
        }

        val plaintext = normalizedHex.hexToBytes()
        rfCodeValidator.validate(DeviceKind.RECEIVER_433M_PASSIVE, request.rfCodeBits, plaintext)
        rfCodeForbiddenSet.assertAllowed(DeviceKind.RECEIVER_433M_PASSIVE, request.rfCodeBits, plaintext)

        val collision = deviceRfCodeRepository.findMaskedCollision(
            storeId = request.storeId,
            candidatePlaintext = plaintext,
            candidateBits = request.rfCodeBits
        )
        if (collision != null) {
            throw PassiveDeviceConflictException(collision.publicId ?: collision.deviceId.toString())
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val saved = deviceRepository.save(
            Device(
                publicId = devicePublicIdMinter.mint(DeviceKind.RECEIVER_433M_PASSIVE),
                publicKeyDer = null,
                kind = DeviceKind.RECEIVER_433M_PASSIVE,
                status = DeviceStatus.ACTIVE,
                assignedName = request.assignedName.trim(),
                storeId = request.storeId,
                firmwareVersion = null,
                lastSeenAt = null,
                activatedAt = now
            )
        )

        deviceRfCodeRepository.insert(
            deviceId = saved.id!!,
            plaintext = plaintext,
            bits = 16,
            version = 1,
            issuedAt = now,
            ack = DeviceRfAckStatus.APPLIED,
            ackAt = now
        )

        return deviceQueryService.getRequiredDeviceById(saved.id)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private val HEX_REGEX = Regex("^[0-9A-F]+$")
    }
}
