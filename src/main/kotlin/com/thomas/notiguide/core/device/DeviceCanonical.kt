package com.thomas.notiguide.core.device

import java.time.OffsetDateTime
import java.util.UUID

object DeviceCanonical {
    fun activate(
        registrationNonce: String,
        nonce: String,
        issuedAt: OffsetDateTime,
        expiresAt: OffsetDateTime
    ): String = "activate-v1|$registrationNonce|$nonce|${issuedAt.toInstant()}|${expiresAt.toInstant()}"

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

    fun rosterAck(
        hubPublicId: String,
        seq: Int,
        issuedAt: OffsetDateTime
    ): String = "roster-ack-v1|$hubPublicId|$seq|${issuedAt.toInstant()}"

    fun slotDispatch(
        hubPublicId: String,
        dispatchId: UUID,
        slot: Int,
        action: String,
        issuedAt: OffsetDateTime
    ): String = "dispatch-v1|$hubPublicId|$dispatchId|$slot|$action|${issuedAt.toInstant()}"

    data class RosterCanonicalReceiver(val slot: Int, val band: String, val label: String)

    fun rosterUpdate(
        hubPublicId: String,
        seq: Int,
        receivers: List<RosterCanonicalReceiver>
    ): String {
        val head = "roster-update-v1|$hubPublicId|$seq"
        if (receivers.isEmpty()) return head
        val body = receivers.sortedBy { it.slot }
            .joinToString("|") { "${it.slot}:${it.band}:${it.label}" }
        return "$head|$body"
    }

    fun ack(
        hubPublicId: String,
        ackFor: String,
        id: String,
        status: String
    ): String = "ack-v1|$hubPublicId|$ackFor|$id|$status"

    fun heartbeat(
        hubPublicId: String,
        issuedAtRaw: String,
        heapPct: Int,
        rssi: String,
        uptimeMs: Long,
        dispD: Long,
        dispT: Long,
        ip: String
    ): String = "heartbeat-v1|$hubPublicId|$issuedAtRaw|$heapPct|$rssi|$uptimeMs|$dispD|$dispT|$ip"
}
