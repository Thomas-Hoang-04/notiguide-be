package com.thomas.notiguide.domain.admin.dto

import java.time.OffsetDateTime

data class LoginHistoryDto(
    val id: String,
    val ipAddress: String,
    val success: Boolean,
    val createdAt: OffsetDateTime?
)

