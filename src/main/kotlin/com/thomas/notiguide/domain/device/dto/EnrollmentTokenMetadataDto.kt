package com.thomas.notiguide.domain.device.dto

import java.time.OffsetDateTime
import java.util.UUID

data class EnrollmentTokenMetadataDto(
    val tokenHash: String,
    val storeId: UUID?,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime
)
