package com.thomas.notiguide.domain.device.dto

import java.time.OffsetDateTime

data class EnrollmentTokenIssueResponse(
    val token: String,
    val tokenHash: String,
    val expiresAt: OffsetDateTime
)
