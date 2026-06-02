package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.StorePublicId
import com.thomas.notiguide.domain.store.types.SlugStatus
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface StorePublicIdRepository : CoroutineCrudRepository<StorePublicId, UUID> {

    // Resolution: active + grace resolve transparently. Status literals need no cast.
    @Query("SELECT * FROM store_public_id WHERE lower(slug) = lower(:slug) AND status IN ('ACTIVE', 'GRACE')")
    suspend fun findResolvableBySlug(slug: String): StorePublicId?

    // Global uniqueness pre-check (any status, any store).
    @Query("SELECT * FROM store_public_id WHERE lower(slug) = lower(:slug)")
    suspend fun findAnyBySlug(slug: String): StorePublicId?

    // Locate a specific slug within a store for retire/delete (case-insensitive).
    @Query("SELECT * FROM store_public_id WHERE store_id = :storeId AND lower(slug) = lower(:slug)")
    suspend fun findByStoreIdAndSlug(storeId: UUID, slug: String): StorePublicId?

    // Listing: default first, then oldest alias first.
    @Query("SELECT * FROM store_public_id WHERE store_id = :storeId ORDER BY is_default DESC, created_at")
    fun findByStoreId(storeId: UUID): Flow<StorePublicId>

    // Oldest active non-default alias — the auto-retire victim when at the active cap.
    @Query("SELECT * FROM store_public_id WHERE store_id = :storeId AND is_default = FALSE AND status = 'ACTIVE' ORDER BY created_at LIMIT 1")
    suspend fun findOldestActiveAlias(storeId: UUID): StorePublicId?

    // Cap counting — enum param requires CAST to the Postgres enum type.
    @Query("SELECT COUNT(*) FROM store_public_id WHERE store_id = :storeId AND status = CAST(:status AS slug_status)")
    suspend fun countByStoreIdAndStatus(storeId: UUID, status: SlugStatus): Long

    // Grace purge — status literal, binds only the timestamp.
    @Modifying
    @Query("DELETE FROM store_public_id WHERE status = 'GRACE' AND expires_at <= :now")
    suspend fun deleteExpiredGrace(now: OffsetDateTime): Int
}
