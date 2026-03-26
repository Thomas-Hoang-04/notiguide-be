package com.thomas.notiguide.domain.admin.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UpdateUsernameRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 100)
    @field:Pattern(
        regexp = "^[a-zA-Z0-9_]+$",
        message = "Username must contain only letters, digits, and underscores"
    )
    val username: String
)
