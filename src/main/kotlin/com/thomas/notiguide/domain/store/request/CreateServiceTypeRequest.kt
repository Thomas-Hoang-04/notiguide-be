package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateServiceTypeRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:NotBlank
    @field:Size(max = 5)
    val prefix: String
)
