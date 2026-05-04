package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.domain.device.controller.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.types.DeviceKind
import org.springframework.stereotype.Component

@Component
class RfCodeValidator {

    fun validate(kind: DeviceKind, bits: Int, plaintext: ByteArray) {
        when (kind) {
            DeviceKind.RECEIVER_433M -> {
                if (bits !in 1..32 || plaintext.size != ceilDiv(bits)) {
                    throw DeviceBadRequestEnvelopeException("width_out_of_range")
                }
            }
            DeviceKind.RECEIVER_433M_PASSIVE -> {
                if (bits != 16 || plaintext.size != 2) {
                    throw DeviceBadRequestEnvelopeException("width_out_of_range")
                }
            }
            DeviceKind.RECEIVER_2_4G -> {
                if (bits != 40 || plaintext.size != 5) {
                    throw DeviceBadRequestEnvelopeException("width_out_of_range")
                }
            }
            DeviceKind.TRANSMITTER_HUB -> {
                throw IllegalArgumentException("Transmitter hubs do not own RF codes")
            }
        }
    }

    private fun ceilDiv(numerator: Int): Int =
        (numerator + 8 - 1) / 8
}
