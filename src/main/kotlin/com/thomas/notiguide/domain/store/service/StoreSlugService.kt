package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.store.StoreSlugProperties
import com.thomas.notiguide.domain.store.dto.StoreSlugDto
import com.thomas.notiguide.domain.store.response.StoreSlugListResponse
import com.thomas.notiguide.domain.store.entity.StorePublicId
import com.thomas.notiguide.domain.store.repository.StorePublicIdRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.request.CreateSlugRequest
import com.thomas.notiguide.domain.store.types.SlugStatus
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class StoreSlugService(
    private val storePublicIdRepository: StorePublicIdRepository,
    private val storeRepository: StoreRepository,
    storeSlugProperties: StoreSlugProperties
) {
    companion object {
        const val ACTIVE_CAP = 5  // includes the immutable default → 4 active aliases
        const val GRACE_CAP = 5
    }

    private val gracePeriodDays = storeSlugProperties.gracePeriodDays

    @Transactional(readOnly = true)
    suspend fun listSlugs(storeId: UUID): StoreSlugListResponse {
        storeRepository.findById(storeId) ?: throw NotFoundException("Store", "id", storeId.toString())
        val items = storePublicIdRepository.findByStoreId(storeId).toList().map { it.toDto() }
        return StoreSlugListResponse(
            items = items,
            activeCount = items.count { it.status == SlugStatus.ACTIVE.name },
            activeMax = ACTIVE_CAP,
            graceCount = items.count { it.status == SlugStatus.GRACE.name },
            graceMax = GRACE_CAP
        )
    }

    @Transactional
    suspend fun createAlias(storeId: UUID, request: CreateSlugRequest): StoreSlugDto {
        storeRepository.findById(storeId) ?: throw NotFoundException("Store", "id", storeId.toString())
        val slug = request.slug.trim()

        // Validate + uniqueness BEFORE any retire, so a bad/taken new slug can
        // never cost the admin an existing one.
        SlugValidator.validate(slug)
        if (storePublicIdRepository.findAnyBySlug(slug) != null) {
            throw ConflictException("Slug '$slug' is already taken")
        }

        val activeCount = storePublicIdRepository.countByStoreIdAndStatus(storeId, SlugStatus.ACTIVE)
        if (activeCount >= ACTIVE_CAP) {
            val graceCount = storePublicIdRepository.countByStoreIdAndStatus(storeId, SlugStatus.GRACE)
            if (graceCount >= GRACE_CAP) {
                throw ConflictException(
                    "Both the active and retiring limits are full. Remove a link immediately or wait for one to expire."
                )
            }
            if (!request.confirmAutoRetire) {
                throw ConflictException(
                    "Adding a link here will retire the oldest one. Confirm to proceed."
                )
            }
            // Atomically retire the oldest active alias (recomputed server-side).
            val oldest = storePublicIdRepository.findOldestActiveAlias(storeId)
                ?: throw ConflictException("No retireable link is available.")
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            storePublicIdRepository.save(
                oldest.copy(
                    status = SlugStatus.GRACE,
                    retiredAt = now,
                    expiresAt = now.plusDays(gracePeriodDays)
                )
            )
        }

        val saved = storePublicIdRepository.save(
            StorePublicId(storeId = storeId, slug = slug, isDefault = false, status = SlugStatus.ACTIVE)
        )
        return saved.toDto()
    }

    @Transactional
    suspend fun retireAlias(storeId: UUID, slug: String): StoreSlugDto {
        val row = storePublicIdRepository.findByStoreIdAndSlug(storeId, slug.trim())
            ?: throw NotFoundException("Slug", "slug", slug)
        if (row.isDefault) throw ConflictException("The default identifier cannot be retired")
        if (row.status == SlugStatus.GRACE) throw ConflictException("Slug is already retiring")
        if (storePublicIdRepository.countByStoreIdAndStatus(storeId, SlugStatus.GRACE) >= GRACE_CAP) {
            throw ConflictException("Retiring slug limit reached ($GRACE_CAP). Remove one immediately first.")
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val updated = row.copy(
            status = SlugStatus.GRACE,
            retiredAt = now,
            expiresAt = now.plusDays(gracePeriodDays)
        )
        return storePublicIdRepository.save(updated).toDto()
    }

    @Transactional
    suspend fun hardDeleteAlias(storeId: UUID, slug: String) {
        val row = storePublicIdRepository.findByStoreIdAndSlug(storeId, slug.trim())
            ?: throw NotFoundException("Slug", "slug", slug)
        if (row.isDefault) throw ConflictException("The default identifier cannot be deleted")
        storePublicIdRepository.delete(row)
    }
}
