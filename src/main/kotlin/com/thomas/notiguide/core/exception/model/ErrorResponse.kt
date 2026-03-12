package com.thomas.notiguide.core.exception.model

import java.time.LocalDateTime

data class ErrorResponse(
    val timestamp: LocalDateTime,
    val code: Int,
    val error: String,
    val message: String,
    val path: String,
    val method: String,
    val details: Map<String, String>? = null
)
