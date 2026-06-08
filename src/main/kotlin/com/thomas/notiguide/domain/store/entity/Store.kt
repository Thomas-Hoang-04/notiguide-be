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

    @Column("public_id")
    val publicId: String? = null,

    @Column("org_id")
    val orgId: UUID? = null,

    @Column("join_code")
    val joinCode: String? = null,

    @Column("name")
    val name: String,

    @Column("address")
    val address: String? = null,

    @Column("is_active")
    val isActive: Boolean = true,

    @Column("allow_jump_call")
    val allowJumpCall: Boolean = false,

    @Column("allow_no_show")
    val allowNoShow: Boolean = false,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: OffsetDateTime? = null
) {

    fun toDto(): StoreDto = StoreDto(
        id = id!!,
        publicId = publicId!!,
        orgId = orgId,
        name = name,
        address = address,
        isActive = isActive,
        allowJumpCall = allowJumpCall,
        allowNoShow = allowNoShow,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
