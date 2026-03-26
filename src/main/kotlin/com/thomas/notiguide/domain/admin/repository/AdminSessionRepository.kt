package com.thomas.notiguide.domain.admin.repository

import com.thomas.notiguide.domain.admin.entity.AdminSession
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface AdminSessionRepository : CoroutineCrudRepository<AdminSession, UUID> {
    fun findByAdminIdOrderByLastActiveDesc(adminId: UUID): Flow<AdminSession>
    suspend fun findByTokenHash(tokenHash: String): AdminSession?
    suspend fun deleteByTokenHash(tokenHash: String)
    suspend fun deleteByAdminId(adminId: UUID)
}
