package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.domain.device.controller.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.types.DeviceKind
import org.springframework.stereotype.Component
import java.math.BigInteger

@Component
class RfCodeForbiddenSet {

    fun assertAllowed(kind: DeviceKind, bits: Int, plaintext: ByteArray) {
        when (kind) {
            DeviceKind.RECEIVER_433M,
            DeviceKind.RECEIVER_433M_PASSIVE -> {
                val relevantHex = toRelevantHex(bits, plaintext)
                if (relevantHex.length >= 2 && relevantHex.all { it == relevantHex.first() }) {
                    throw DeviceBadRequestEnvelopeException("forbidden_pattern")
                }
            }
            DeviceKind.RECEIVER_2_4G -> {
                val hex = plaintext.toHex()
                if (hex == "0000000000" || hex == "FFFFFFFFFF" || hex == "5555555555" || hex == "AAAAAAAAAA") {
                    throw DeviceBadRequestEnvelopeException("forbidden_pattern")
                }
                if (countByteTransitions(plaintext) <= 1) {
                    throw DeviceBadRequestEnvelopeException("forbidden_pattern")
                }
                if (countBitTransitions(plaintext.first()) < 2) {
                    throw DeviceBadRequestEnvelopeException("forbidden_pattern")
                }
            }
            DeviceKind.TRANSMITTER_HUB -> {
                throw IllegalArgumentException("Transmitter hubs do not own RF codes")
            }
        }
    }

    private fun countByteTransitions(plaintext: ByteArray): Int =
        (0 until plaintext.size - 1).count { plaintext[it] != plaintext[it + 1] }

    private fun countBitTransitions(firstByte: Byte): Int {
        var transitions = 0
        var previous = (firstByte.toInt() ushr 7) and 1
        for (bit in 6 downTo 0) {
            val current = (firstByte.toInt() ushr bit) and 1
            if (current != previous) {
                transitions += 1
            }
            previous = current
        }
        return transitions
    }

    private fun toRelevantHex(bits: Int, plaintext: ByteArray): String {
        val relevantBits = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val masked = BigInteger(1, plaintext).and(relevantBits)
        return masked.toString(16).uppercase().padStart((bits + 3) / 4, '0')
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }
}
