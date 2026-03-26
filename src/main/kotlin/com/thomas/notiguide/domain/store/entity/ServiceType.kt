package com.thomas.notiguide.domain.store.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("service_type")
data class ServiceType(
    @Id val id: UUID? = null,
    val storeId: UUID,
    val name: String,
    val prefix: String,
    val isActive: Boolean = true,
    @CreatedDate val createdAt: OffsetDateTime? = null,
    @LastModifiedDate val updatedAt: OffsetDateTime? = null
)
