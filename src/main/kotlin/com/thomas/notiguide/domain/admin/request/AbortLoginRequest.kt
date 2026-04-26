package com.thomas.notiguide.domain.admin.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AbortLoginRequest(
    @field:NotBlank
    @field:Size(max = 256)
    val abortToken: String
)
