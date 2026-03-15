package com.thomas.notiguide.domain.admin.request

import com.thomas.notiguide.domain.admin.types.AdminRole
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateAdminRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 100)
    @field:Pattern(
        regexp = "^[a-zA-Z0-9_]+$",
        message = "Username must contain only letters, digits, and underscores"
    )
    val username: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    val password: String,
    val role: AdminRole,
    val storeId: UUID?
)
