package com.thomas.notiguide.domain.device.entity

import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("device")
data class Device(
    @Id
    @Column("id")
    val id: UUID? = null,

    @Column("public_id")
    val publicId: String? = null,

    @Column("public_key_der")
    val publicKeyDer: ByteArray? = null,

    @Column("kind")
    val kind: DeviceKind,

    @Column("status")
    val status: DeviceStatus = DeviceStatus.PENDING,

    @Column("assigned_name")
    val assignedName: String? = null,

    @Column("store_id")
    val storeId: UUID? = null,

    @Column("firmware_version")
    val firmwareVersion: String? = null,

    @Column("last_seen_at")
    val lastSeenAt: OffsetDateTime? = null,

    @Column("activated_at")
    val activatedAt: OffsetDateTime? = null,

    @Column("hub_slot")
    val hubSlot: Short? = null,

    @Column("last_roster_seq")
    val lastRosterSeq: Int? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: OffsetDateTime? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Device

        if (id != other.id) return false
        if (publicId != other.publicId) return false
        if (!publicKeyDer.contentEquals(other.publicKeyDer)) return false
        if (kind != other.kind) return false
        if (status != other.status) return false
        if (assignedName != other.assignedName) return false
        if (storeId != other.storeId) return false
        if (firmwareVersion != other.firmwareVersion) return false
        if (lastSeenAt != other.lastSeenAt) return false
        if (activatedAt != other.activatedAt) return false
        if (hubSlot != other.hubSlot) return false
        if (lastRosterSeq != other.lastRosterSeq) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (publicId?.hashCode() ?: 0)
        result = 31 * result + (publicKeyDer?.contentHashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (assignedName?.hashCode() ?: 0)
        result = 31 * result + (storeId?.hashCode() ?: 0)
        result = 31 * result + (firmwareVersion?.hashCode() ?: 0)
        result = 31 * result + (lastSeenAt?.hashCode() ?: 0)
        result = 31 * result + (activatedAt?.hashCode() ?: 0)
        result = 31 * result + (hubSlot?.hashCode() ?: 0)
        result = 31 * result + (lastRosterSeq?.hashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        result = 31 * result + (updatedAt?.hashCode() ?: 0)
        return result
    }
}
