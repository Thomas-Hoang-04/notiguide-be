package com.thomas.notiguide.domain.queue.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterFcmTokenRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val token: String
)
