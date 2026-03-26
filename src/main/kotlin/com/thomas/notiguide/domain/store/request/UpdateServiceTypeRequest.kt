package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.Size

data class UpdateServiceTypeRequest(
    @field:Size(max = 100)
    val name: String? = null,

    @field:Size(max = 5)
    val prefix: String? = null,

    val isActive: Boolean? = null
)
