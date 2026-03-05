package com.thomas.notiguide.domain.admin.repository

import com.thomas.notiguide.domain.admin.entity.Admin
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AdminRepository : CoroutineCrudRepository<Admin, UUID> {
    suspend fun findByUsername(username: String): Admin?
    suspend fun existsByUsername(username: String): Boolean
    fun findByStoreId(storeId: UUID): Flow<Admin>
}
