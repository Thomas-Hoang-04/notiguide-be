package com.thomas.notiguide.domain.admin.dto

import java.time.OffsetDateTime

data class AdminSessionDto(
    val id: String,
    val ipAddress: String,
    val userAgent: String?,
    val lastActive: OffsetDateTime?,
    val createdAt: OffsetDateTime?,
    val isCurrent: Boolean
)
