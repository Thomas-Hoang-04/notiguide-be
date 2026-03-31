package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.Store
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface StoreRepository : CoroutineCrudRepository<Store, UUID> {

    @Query("SELECT * FROM store ORDER BY created_at DESC LIMIT :size OFFSET :offset")
    fun findAllPaged(size: Long, offset: Long): Flow<Store>

    @Query("SELECT * FROM store WHERE is_active = true ORDER BY created_at DESC")
    fun findAllActive(): Flow<Store>

    @Query("SELECT * FROM store WHERE public_id = :publicId")
    suspend fun findByPublicId(publicId: String): Store?
}
