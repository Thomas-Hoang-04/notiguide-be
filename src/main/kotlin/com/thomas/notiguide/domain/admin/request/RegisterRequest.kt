package com.thomas.notiguide.domain.admin.request

import com.thomas.notiguide.domain.admin.types.RegisterMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotNull
    val mode: RegisterMode,

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
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9\\s]).+$",
        message = "Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
    )
    val password: String,

    @field:Size(max = 255)
    val orgName: String? = null,

    @field:Size(max = 255)
    val storeName: String? = null,

    @field:Size(max = 1000)
    val storeAddress: String? = null,

    @field:Size(max = 64)
    val inviteToken: String? = null
)
