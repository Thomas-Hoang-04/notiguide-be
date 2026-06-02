package com.thomas.notiguide.domain.store.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateSlugRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 128)
    @field:Pattern(
        regexp = "^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$",
        message = "Slug may contain only letters, digits, and single hyphens"
    )
    val slug: String,

    val confirmAutoRetire: Boolean = false
)
