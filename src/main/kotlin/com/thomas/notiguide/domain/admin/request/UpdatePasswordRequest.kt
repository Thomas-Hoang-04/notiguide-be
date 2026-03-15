package com.thomas.notiguide.domain.admin.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UpdatePasswordRequest(
    @field:NotBlank val oldPassword: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    val newPassword: String
)
