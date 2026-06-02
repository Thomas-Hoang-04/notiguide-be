package com.thomas.notiguide.domain.store.entity

import com.thomas.notiguide.domain.store.dto.StoreSlugDto
import com.thomas.notiguide.domain.store.types.SlugStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("store_public_id")
data class StorePublicId(
    @Id
    @Column("id")
    val id: UUID? = null,

    @Column("store_id")
    val storeId: UUID,

    @Column("slug")
    val slug: String,

    @Column("is_default")
    val isDefault: Boolean = false,

    @Column("status")
    val status: SlugStatus = SlugStatus.ACTIVE,

    @Column("retired_at")
    val retiredAt: OffsetDateTime? = null,

    @Column("expires_at")
    val expiresAt: OffsetDateTime? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null
) {
    fun toDto(): StoreSlugDto = StoreSlugDto(
        slug = slug,
        isDefault = isDefault,
        status = status.name,
        retiredAt = retiredAt,
        expiresAt = expiresAt,
        createdAt = createdAt
    )
}
