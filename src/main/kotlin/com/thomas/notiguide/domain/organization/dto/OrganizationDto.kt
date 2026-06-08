package com.thomas.notiguide.domain.organization.dto

import java.time.OffsetDateTime
import java.util.UUID

data class OrganizationDto(
    val id: UUID,
    val name: String,
    val joinCode: String,
    val createdBy: UUID?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)
