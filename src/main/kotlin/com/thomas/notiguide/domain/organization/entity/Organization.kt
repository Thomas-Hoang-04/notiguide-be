package com.thomas.notiguide.domain.organization.entity

import com.thomas.notiguide.domain.organization.dto.OrganizationDto
import com.thomas.notiguide.domain.organization.dto.OrganizationPublicDto
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("organization")
data class Organization(
    @Id
    @Column("id")
    val id: UUID? = null,

    @Column("name")
    val name: String,

    @Column("join_code")
    val joinCode: String,

    @Column("created_by")
    val createdBy: UUID? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: OffsetDateTime? = null
) {
    fun toDto(): OrganizationDto = OrganizationDto(
        id = id!!,
        name = name,
        joinCode = joinCode,
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun toPublicDto(): OrganizationPublicDto = OrganizationPublicDto(
        id = id!!,
        name = name,
        createdAt = createdAt
    )
}
