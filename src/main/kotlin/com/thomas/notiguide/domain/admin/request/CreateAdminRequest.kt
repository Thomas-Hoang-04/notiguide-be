package com.thomas.notiguide.domain.admin.request

import com.thomas.notiguide.domain.admin.types.AdminRole
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateAdminRequest(
    @field:NotBlank @field:Size(min = 3, max = 100) val username: String,
    @field:NotBlank @field:Size(min = 8) val password: String,
    val role: AdminRole,
    val storeId: UUID?
)
