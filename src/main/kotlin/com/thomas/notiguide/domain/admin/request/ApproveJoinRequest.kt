package com.thomas.notiguide.domain.admin.request

import com.thomas.notiguide.domain.admin.types.AdminRole
import java.util.UUID

data class ApproveJoinRequest(
    val storeId: UUID? = null,
    val role: AdminRole = AdminRole.ROLE_ADMIN,
)
