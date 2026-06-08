package com.thomas.notiguide.domain.admin.response

import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.admin.types.RegisterStatus

data class RegisterResponse(
    val status: RegisterStatus,
    val role: AdminRole?,
    val targetType: String? = null
)
