package com.thomas.notiguide.domain.admin.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("login_history")
data class LoginHistory(
    @Id val id: UUID? = null,
    val adminId: UUID,
    val ipAddress: String,
    val success: Boolean,
    val createdAt: OffsetDateTime? = null
)
