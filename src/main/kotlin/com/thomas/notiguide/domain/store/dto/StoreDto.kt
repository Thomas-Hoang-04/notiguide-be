package com.thomas.notiguide.domain.store.dto

import java.time.OffsetDateTime
import java.util.UUID

data class StoreDto(
    val id: UUID,
    val name: String,
    val address: String?,
    val isActive: Boolean,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)
