package com.thomas.notiguide.domain.store.dto

import java.time.OffsetDateTime

data class ServiceTypeDto(
    val id: String,
    val storeId: String,
    val name: String,
    val prefix: String,
    val isActive: Boolean,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)

