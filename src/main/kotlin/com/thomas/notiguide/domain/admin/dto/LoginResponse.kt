package com.thomas.notiguide.domain.admin.dto

data class LoginResponse(
    val token: String,
    val admin: AdminDto
)
