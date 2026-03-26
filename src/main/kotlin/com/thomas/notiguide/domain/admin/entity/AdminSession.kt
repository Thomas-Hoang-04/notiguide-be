package com.thomas.notiguide.domain.admin.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("admin_session")
data class AdminSession(
    @Id val id: UUID? = null,
    val adminId: UUID,
    val tokenHash: String,
    val ipAddress: String,
    val userAgent: String?,
    val lastActive: OffsetDateTime? = null,
    val createdAt: OffsetDateTime? = null
)
