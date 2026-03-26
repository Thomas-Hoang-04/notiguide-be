package com.thomas.notiguide.domain.admin.dto

import com.thomas.notiguide.domain.admin.types.AdminRole
import java.time.OffsetDateTime
import java.util.UUID

data class AdminDto(
    val id: UUID,
    val username: String,
    val role: AdminRole,
    val storeId: UUID?,
    val storeName: String? = null,
    val isVerified: Boolean,
    val createdBy: UUID?,
    val verifiedBy: UUID?,
    val verifiedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)
