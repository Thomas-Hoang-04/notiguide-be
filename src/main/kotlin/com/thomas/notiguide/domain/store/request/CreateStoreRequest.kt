package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateStoreRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 1000) val address: String? = null
)
