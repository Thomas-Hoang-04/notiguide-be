package com.thomas.notiguide.core.device

import java.time.OffsetDateTime
import java.util.UUID

object DeviceCanonical {
    fun activate(
        challengeId: UUID,
        nonce: String,
        issuedAt: OffsetDateTime,
        expiresAt: OffsetDateTime
    ): String = "activate-v1|$challengeId|$nonce|${issuedAt.toInstant()}|${expiresAt.toInstant()}"

    fun rfCode(
        publicId: String,
        codeVersion: Int,
        rfCodeHex: String,
        rfCodeBits: Int,
        issuedAt: OffsetDateTime
    ): String = "rf-code-v1|$publicId|$codeVersion|$rfCodeHex|$rfCodeBits|${issuedAt.toInstant()}"

    fun deact(
        publicId: String,
        commandId: UUID,
        action: String,
        issuedAt: OffsetDateTime
    ): String = "deact-v1|$publicId|$commandId|$action|${issuedAt.toInstant()}"

    fun transmit(
        hubPublicId: String,
        dispatchId: UUID,
        receiverPublicId: String,
        band: String,
        rfCodeHex: String,
        rfCodeBits: Int,
        protoAny: Boolean,
        issuedAt: OffsetDateTime
    ): String = "transmit-v1|$hubPublicId|$dispatchId|$receiverPublicId|$band|$rfCodeHex|$rfCodeBits|$protoAny|${issuedAt.toInstant()}"
}
