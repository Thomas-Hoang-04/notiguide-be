package com.thomas.notiguide.domain.admin.entity

import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.shared.principal.AdminPrincipal
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.OffsetDateTime
import java.util.UUID

@Table("admin")
data class Admin(
    @Id
    @Column("id")
    val id: UUID? = null,

    @Column("username")
    val username: String,

    @Column("password_hash")
    val passwordHash: String,

    @Column("role")
    val role: AdminRole = AdminRole.ROLE_ADMIN,

    @Column("store_id")
    val storeId: UUID? = null,

    @Column("is_verified")
    val isVerified: Boolean = false,

    @CreatedBy
    @Column("created_by")
    val createdBy: UUID? = null,

    @Column("verified_by")
    val verifiedBy: UUID? = null,

    @Column("verified_at")
    val verifiedAt: OffsetDateTime? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: OffsetDateTime? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: OffsetDateTime? = null
) {

    fun toDto(storeName: String? = null): AdminDto = AdminDto(
        id = id!!,
        username = username,
        role = role,
        storeId = storeId,
        storeName = storeName,
        isVerified = isVerified,
        createdBy = createdBy,
        verifiedBy = verifiedBy,
        verifiedAt = verifiedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun toPrincipal(): AdminPrincipal = AdminPrincipal(
        _id = id!!,
        _username = username,
        _password = passwordHash,
        _authorities = listOf(SimpleGrantedAuthority(role.name)),
        storeId = storeId,
        isVerified = isVerified
    )
}
