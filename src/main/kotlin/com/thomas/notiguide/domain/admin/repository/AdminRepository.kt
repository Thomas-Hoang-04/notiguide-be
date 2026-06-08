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

    @Query("SELECT COUNT(*) FROM admin WHERE store_id = :storeId")
    suspend fun countByStoreId(storeId: UUID): Long

    @Query("SELECT * FROM admin WHERE store_id = :storeId AND role = CAST(:role AS admin_role) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findByStoreIdAndRolePaged(storeId: UUID, role: AdminRole, limit: Long, offset: Long): Flow<Admin>

    @Query("SELECT COUNT(*) FROM admin WHERE store_id = :storeId AND role = CAST(:role AS admin_role)")
    suspend fun countByStoreIdAndRole(storeId: UUID, role: AdminRole): Long

    @Query("SELECT COUNT(*) FROM admin WHERE org_id = :orgId AND role = CAST(:role AS admin_role)")
    suspend fun countByOrgIdAndRole(orgId: UUID, role: AdminRole): Long
    // Org membership rules: own org_id (SUPER_ADMIN), store's org_id (assigned ADMIN),
    // creator's org_id (unassigned ADMIN with store_id NULL attributed via created_by)
    @Query(
        """
        SELECT a.* FROM admin a
        LEFT JOIN store s ON a.store_id = s.id
        WHERE a.org_id = :orgId
           OR s.org_id = :orgId
           OR (a.role = 'ROLE_ADMIN' AND a.store_id IS NULL AND EXISTS (
               SELECT 1 FROM admin c WHERE c.id = a.created_by AND c.org_id = :orgId))
        ORDER BY a.created_at DESC LIMIT :limit OFFSET :offset
        """
    )
    fun findByOrgPaged(orgId: UUID, limit: Long, offset: Long): Flow<Admin>
    @Query(
        """
        SELECT COUNT(*) FROM admin a
        LEFT JOIN store s ON a.store_id = s.id
        WHERE a.org_id = :orgId
           OR s.org_id = :orgId
           OR (a.role = 'ROLE_ADMIN' AND a.store_id IS NULL AND EXISTS (
               SELECT 1 FROM admin c WHERE c.id = a.created_by AND c.org_id = :orgId))
        """
    )
    suspend fun countByOrg(orgId: UUID): Long
}
