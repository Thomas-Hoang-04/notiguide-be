package com.thomas.notiguide.domain.admin.dto

data class LoginResponse(
    val admin: AdminDto,
    val sessionId: String? = null
)
