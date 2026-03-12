package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.NotBlank

data class CreateStoreRequest(
    @field:NotBlank val name: String,
    val address: String? = null
)
