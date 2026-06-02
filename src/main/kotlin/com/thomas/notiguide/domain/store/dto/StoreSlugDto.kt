package com.thomas.notiguide.domain.store.dto

import java.time.OffsetDateTime

data class StoreSlugDto(
    val slug: String,
    val isDefault: Boolean,
    val status: String,
    val retiredAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
    val createdAt: OffsetDateTime?
)

