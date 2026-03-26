package com.thomas.notiguide.domain.store.entity

import com.thomas.notiguide.domain.store.dto.StoreDto
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("store")
data class Store(
    @Id
    @Column("id")
    val id: UUID? = null,

    @Column("name")
    val name: String,

    @Column("address")
    val address: String? = null,

    @Column("is_active")
    val isActive: Boolean = true,

    @Column("allow_jump_call")
    val allowJumpCall: Boolean = false,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: OffsetDateTime? = null
) {

    fun toDto(): StoreDto = StoreDto(
        id = id!!,
        name = name,
        address = address,
        isActive = isActive,
        allowJumpCall = allowJumpCall,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
