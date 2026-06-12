package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.Store
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface StoreRepository : CoroutineCrudRepository<Store, UUID> {

    @Query("SELECT * FROM store WHERE is_active = true ORDER BY created_at DESC")
    fun findAllActive(): Flow<Store>

    @Query("SELECT org_id FROM store WHERE id = :storeId")
    suspend fun findOrgIdByStoreId(storeId: UUID): UUID?

    @Query("SELECT * FROM store WHERE org_id = :orgId ORDER BY created_at DESC LIMIT :size OFFSET :offset")
    fun findByOrgIdPaged(orgId: UUID, size: Long, offset: Long): Flow<Store>

    @Query("SELECT COUNT(*) FROM store WHERE org_id = :orgId")
    suspend fun countByOrgId(orgId: UUID): Long

    @Query("SELECT * FROM store WHERE org_id = :orgId AND is_active = true ORDER BY created_at DESC")
    fun findActiveByOrgId(orgId: UUID): Flow<Store>

    @Query("SELECT id FROM store WHERE org_id = :orgId")
    fun findIdsByOrgId(orgId: UUID): Flow<UUID>

}
