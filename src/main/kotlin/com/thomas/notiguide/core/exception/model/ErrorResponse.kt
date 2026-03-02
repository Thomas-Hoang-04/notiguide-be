package com.thomas.notiguide.core.exception.model

import java.time.LocalDateTime

data class ErrorResponse<T>(
    val timestamp: LocalDateTime,
    val code: Int,
    val error: Class<T>,
    val message: String,
    val path: String,
    val method: String
)
