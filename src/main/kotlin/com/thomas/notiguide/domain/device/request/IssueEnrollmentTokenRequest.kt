package com.thomas.notiguide.domain.device.request

import java.util.UUID

data class IssueEnrollmentTokenRequest(
    val storeId: UUID? = null
)
