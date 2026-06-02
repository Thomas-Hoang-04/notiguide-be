package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.response.StorePageResponse
import com.thomas.notiguide.domain.store.dto.StoreSettingsDto
import com.thomas.notiguide.domain.store.entity.ServiceType
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.entity.StoreSettings
import com.thomas.notiguide.domain.store.repository.ServiceTypeRepository
import com.thomas.notiguide.domain.store.repository.StorePublicIdRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.repository.StoreSettingsRepository
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreSettingsRequest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class StoreService(
    private val storeRepository: StoreRepository,
    private val storePublicIdRepository: StorePublicIdRepository,
    private val adminRepository: AdminRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val queueService: QueueService,
    private val storeSettingsRepository: StoreSettingsRepository,
    private val serviceTypeRepository: ServiceTypeRepository,
    private val redis: ReactiveRedisTemplate<String, String>
) {

    @Transactional(readOnly = true)
    suspend fun listStores(page: Int, size: Int): StorePageResponse {
        require(page >= 0) { "Page must be >= 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }

        val totalItems = storeRepository.count()
        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        val offset = page.toLong() * size

        val items = if (offset >= totalItems) {
            emptyList()
        } else {
            storeRepository.findAllPaged(size.toLong(), offset)
                .map { it.toDto() }
                .toList()
        }

        return StorePageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages
        )
    }

    @Transactional(readOnly = true)
    suspend fun getStore(id: UUID): StoreDto {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())
        return store.toDto()
    }

    data class StoreResolution(val store: StoreDto, val matchedSlug: String, val isDefault: Boolean)

    @Transactional(readOnly = true)
    suspend fun resolvePublicId(input: String): StoreResolution? {
        val normalized = input.trim()

        storePublicIdRepository.findResolvableBySlug(normalized)?.let { row ->
            val store = storeRepository.findById(row.storeId) ?: return null
            return StoreResolution(store.toDto(), row.slug, row.isDefault)
        }

        runCatching { UUID.fromString(normalized) }.getOrNull()
            ?.let { storeRepository.findById(it) }
            ?.let { return StoreResolution(it.toDto(), it.publicId!!, true) }

        return null
    }

    @Transactional(readOnly = true)
    suspend fun getStoreByPublicId(publicId: String): StoreDto =
        resolvePublicId(publicId)?.store
            ?: throw NotFoundException("Store", "publicId", publicId)

    @Transactional
    suspend fun createStore(request: CreateStoreRequest): StoreDto {
        require(request.name.isNotBlank()) { "Store name must not be blank" }
        validateNoShowAction(request.noShowAction)

        val store = Store(
            name = request.name,
            address = request.address?.takeIf { it.isNotBlank() },
            allowJumpCall = request.allowJumpCall,
            allowNoShow = request.allowNoShow
        )
        val saved = storeRepository.save(store)

        storeSettingsRepository.save(
            StoreSettings(
                storeId = saved.id!!,
                maxQueueSize = request.maxQueueSize,
                gracePeriodSec = request.gracePeriodSec,
                noShowAction = request.noShowAction,
                maxRequeues = request.maxRequeues,
                requeueOffset = request.requeueOffset,
                alertThreshold = request.alertThreshold
            ).markNew()
        )

        serviceTypeRepository.save(ServiceType(storeId = saved.id, name = "General", prefix = "A"))
        val complete = storeRepository.findById(saved.id)
            ?: throw IllegalStateException("Store not found after save")
        return complete.toDto()
    }

    @Transactional
    suspend fun updateStore(id: UUID, request: UpdateStoreRequest): StoreDto {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())

        request.name?.let {
            require(it.isNotBlank()) { "Store name must not be blank" }
        }

        val updated = store.copy(
            name = request.name ?: store.name,
            address = if (request.addressProvided) request.address?.takeIf { it.isNotBlank() } else store.address,
            isActive = request.isActive ?: store.isActive,
            allowJumpCall = request.allowJumpCall ?: store.allowJumpCall,
            allowNoShow = request.allowNoShow ?: store.allowNoShow
        )
        return storeRepository.save(updated).toDto()
    }

    @Transactional
    suspend fun deleteStore(id: UUID) {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())

        if (adminRepository.countByStoreId(id) > 0)
            throw ConflictException("Store has assigned admins. Remove or reassign admins before deleting the store.")

        val queueSize = redisQueueRepository.getQueueSize(id)
        val servingTickets = redisQueueRepository.getServingTickets(id).toList()
        if (queueSize > 0 || servingTickets.isNotEmpty())
            throw ConflictException("Store has active queue tickets. Drain or clear the queue before deleting.")

        queueService.clearStoreData(id)
        storeRepository.delete(store)
    }

    @Transactional(readOnly = true)
    suspend fun getStoreSettings(storeId: UUID): StoreSettingsDto {
        val settings = storeSettingsRepository.findById(storeId)
            ?: throw NotFoundException("StoreSettings", "storeId", storeId.toString())
        return settings.toDto()
    }

    @Transactional
    suspend fun updateStoreSettings(storeId: UUID, request: UpdateStoreSettingsRequest): StoreSettingsDto {
        val existing = storeSettingsRepository.findById(storeId)
            ?: throw NotFoundException("StoreSettings", "storeId", storeId.toString())

        request.noShowAction?.let(::validateNoShowAction)

        val updated = existing.copy(
            maxQueueSize = request.maxQueueSize ?: existing.maxQueueSize,
            gracePeriodSec = request.gracePeriodSec ?: existing.gracePeriodSec,
            noShowAction = request.noShowAction ?: existing.noShowAction,
            maxRequeues = request.maxRequeues ?: existing.maxRequeues,
            requeueOffset = request.requeueOffset ?: existing.requeueOffset,
            alertThreshold = request.alertThreshold ?: existing.alertThreshold
        )
        val saved = storeSettingsRepository.save(updated)

        redis.delete(RedisKeyManager.storeSettings(storeId)).awaitSingleOrNull()

        return saved.toDto()
    }

    private fun validateNoShowAction(noShowAction: String) {
        if (noShowAction !in listOf("SKIP", "REQUEUE")) {
            throw IllegalArgumentException("noShowAction must be SKIP or REQUEUE")
        }
    }

    private fun StoreSettings.toDto() = StoreSettingsDto(
        storeId = storeId.toString(),
        maxQueueSize = maxQueueSize,
        gracePeriodSec = gracePeriodSec,
        noShowAction = noShowAction,
        maxRequeues = maxRequeues,
        requeueOffset = requeueOffset,
        alertThreshold = alertThreshold,
        updatedAt = updatedAt
    )
}
