package com.thomas.notiguide.domain.store.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("store_settings")
data class StoreSettings(
    @Id val storeId: UUID,
    val maxQueueSize: Int = 0,
    val gracePeriodSec: Int = 0,
    val noShowAction: String = "SKIP",
    val maxRequeues: Int = 1,
    val requeueOffset: Int = 3,
    val alertThreshold: Int = 2,
    @LastModifiedDate val updatedAt: OffsetDateTime? = null,
    @Transient val isNewEntity: Boolean = false
) : Persistable<UUID> {
    override fun getId(): UUID = storeId
    override fun isNew(): Boolean = isNewEntity
}
