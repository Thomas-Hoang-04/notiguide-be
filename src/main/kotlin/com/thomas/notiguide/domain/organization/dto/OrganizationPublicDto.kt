package com.thomas.notiguide.domain.organization.dto

import java.time.OffsetDateTime
import java.util.UUID

data class OrganizationPublicDto(
    val id: UUID,
    val name: String,
    val createdAt: OffsetDateTime?
)
