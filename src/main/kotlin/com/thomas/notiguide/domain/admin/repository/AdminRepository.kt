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
    @Query("SELECT * FROM admin WHERE LOWER(username) = LOWER(:username)")
    suspend fun findByUsername(username: String): Admin?
    @Query("SELECT EXISTS(SELECT 1 FROM admin WHERE LOWER(username) = LOWER(:username))")
    suspend fun existsByUsername(username: String): Boolean
    @Suppress("unused")
    fun findByStoreId(storeId: UUID): Flow<Admin>
    @Query("SELECT * FROM admin WHERE store_id = :storeId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findByStoreIdPaged(storeId: UUID, limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT * FROM admin ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findAllPaged(limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT * FROM admin WHERE role = CAST(:role AS admin_role) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findByRolePaged(role: AdminRole, limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT COUNT(*) FROM admin WHERE store_id = :storeId")
    suspend fun countByStoreId(storeId: UUID): Long
    @Query("SELECT * FROM admin WHERE store_id = :storeId AND role = CAST(:role AS admin_role) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findByStoreIdAndRolePaged(storeId: UUID, role: AdminRole, limit: Long, offset: Long): Flow<Admin>
    @Query("SELECT COUNT(*) FROM admin WHERE store_id = :storeId AND role = CAST(:role AS admin_role)")
    suspend fun countByStoreIdAndRole(storeId: UUID, role: AdminRole): Long
    suspend fun countByRole(role: AdminRole): Long
}
