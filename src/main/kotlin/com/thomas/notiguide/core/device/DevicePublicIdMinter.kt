package com.thomas.notiguide.core.device

import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceKind
import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class DevicePublicIdMinter(
    private val lookup: DeviceRepository
) {

    private val secureRandom = SecureRandom()

    suspend fun mint(kind: DeviceKind): String {
        val prefix = when (kind) {
            DeviceKind.RECEIVER_433M,
            DeviceKind.RECEIVER_2_4G -> "rcv"
            DeviceKind.RECEIVER_433M_PASSIVE -> "pas"
            DeviceKind.TRANSMITTER_HUB -> "hub"
        }

        repeat(32) {
            val candidate = "$prefix-${randomSuffix()}"
            if (!lookup.existsByPublicId(candidate)) {
                return candidate
            }
        }

        throw IllegalStateException("Unable to mint a unique device public identifier")
    }

    private fun randomSuffix(): String {
        val length = 5
        return buildString(length) {
            repeat(length) {
                append(CROCKFORD_ALPHABET[secureRandom.nextInt(CROCKFORD_ALPHABET.length)])
            }
        }
    }

    companion object {
        private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
