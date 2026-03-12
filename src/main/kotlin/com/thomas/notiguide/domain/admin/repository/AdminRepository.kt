package com.thomas.notiguide.domain.admin.repository

import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.types.AdminRole
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AdminRepository : CoroutineCrudRepository<Admin, UUID> {
    suspend fun findByUsername(username: String): Admin?
    suspend fun existsByUsername(username: String): Boolean
    fun findByStoreId(storeId: UUID): Flow<Admin>
    @Query("SELECT * FROM admin WHERE store_id = :storeId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findByStoreIdPaged(storeId: UUID, limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT * FROM admin ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findAllPaged(limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT COUNT(*) FROM admin WHERE store_id = :storeId")
    suspend fun countByStoreId(storeId: UUID): Long
    suspend fun countByRole(role: AdminRole): Long
}
