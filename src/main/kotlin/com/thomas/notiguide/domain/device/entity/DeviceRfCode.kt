package com.thomas.notiguide.domain.device.entity

import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("device_rf_code")
data class DeviceRfCode(
    @Id
    @Column("device_id")
    val deviceId: UUID,

    @Column("payload")
    val payload: ByteArray,

    @Column("bits")
    val bits: Short,

    @Column("byte_len")
    val byteLen: Short,

    @Column("version")
    val version: Int,

    @Column("issued_at")
    val issuedAt: OffsetDateTime,

    @Column("ack")
    val ack: DeviceRfAckStatus = DeviceRfAckStatus.PENDING,

    @Column("ack_at")
    val ackAt: OffsetDateTime? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeviceRfCode

        if (bits != other.bits) return false
        if (byteLen != other.byteLen) return false
        if (version != other.version) return false
        if (deviceId != other.deviceId) return false
        if (!payload.contentEquals(other.payload)) return false
        if (issuedAt != other.issuedAt) return false
        if (ack != other.ack) return false
        if (ackAt != other.ackAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bits.toInt()
        result = 31 * result + byteLen
        result = 31 * result + version
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + ack.hashCode()
        result = 31 * result + (ackAt?.hashCode() ?: 0)
        return result
    }
}
