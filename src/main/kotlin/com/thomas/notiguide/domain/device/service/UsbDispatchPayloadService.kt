package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.request.UsbDispatchAction
import com.thomas.notiguide.domain.device.request.UsbDispatchPayloadRequest
import com.thomas.notiguide.domain.device.response.UsbDispatchPayloadResponse
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class UsbDispatchPayloadService(
    private val deviceRepository: DeviceRepository,
    private val deviceRfCodeRepository: DeviceRfCodeRepository,
    private val properties: DeviceCommandSigningProperties
) {
    suspend fun preparePayload(
        request: UsbDispatchPayloadRequest,
        principal: AdminPrincipal
    ): UsbDispatchPayloadResponse {
        StoreAccessUtil.requireStoreAccess(principal, request.storeId)

        val device = deviceRepository.findById(request.deviceId)
            ?: throw HttpException(HttpStatus.NOT_FOUND, "device_not_found")

        if (device.storeId != request.storeId) {
            throw HttpException(HttpStatus.FORBIDDEN, "device_store_mismatch")
        }

        if (device.status != DeviceStatus.ACTIVE) {
            throw HttpException(HttpStatus.CONFLICT, "device_not_active")
        }

        if (device.kind.isHub()) {
            throw HttpException(HttpStatus.CONFLICT, "cannot_dispatch_to_hub")
        }

        val publicId = device.publicId
            ?: throw HttpException(HttpStatus.CONFLICT, "device_missing_public_id")

        val decrypted = runCatching {
            deviceRfCodeRepository.findDecryptedPayload(device.id!!)
        }.getOrElse {
            throw HttpException(HttpStatus.CONFLICT, "device_rf_code_unavailable")
        } ?: throw HttpException(HttpStatus.CONFLICT, "device_missing_rf_code")

        val band: String
        val rfCodeHex: String
        val rfCodeBits: Int
        val protoAny: Boolean

        if (device.kind.isPassive()) {
            val channel = when (request.action) {
                UsbDispatchAction.CALL -> properties.rfCode.pt2272ToggleChannel
                UsbDispatchAction.STOP -> properties.rfCode.pt2272DetoggleChannel
            }.toByte()

            val patched = decrypted.plaintext + byteArrayOf(channel)
            band = "433M"
            rfCodeHex = patched.toHex()
            rfCodeBits = 24
            protoAny = false
        } else {
            band = if (device.kind.is24Band()) "2_4G" else "433M"
            rfCodeHex = decrypted.plaintext.toHex()
            rfCodeBits = decrypted.bits
            protoAny = true
        }

        return UsbDispatchPayloadResponse(
            receiverPublicId = publicId,
            band = band,
            rfCodeHex = rfCodeHex,
            rfCodeBits = rfCodeBits,
            protoAny = protoAny
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }
}
