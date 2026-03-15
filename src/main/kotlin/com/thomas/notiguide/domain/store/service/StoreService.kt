package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.dto.StorePageResponse
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreRequest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class StoreService(
    private val storeRepository: StoreRepository,
    private val adminRepository: AdminRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val queueService: QueueService
) {

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

    suspend fun getStore(id: UUID): StoreDto {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())
        return store.toDto()
    }

    @Transactional
    suspend fun createStore(request: CreateStoreRequest): StoreDto {
        require(request.name.isNotBlank()) { "Store name must not be blank" }
        val store = Store(
            name = request.name,
            address = request.address?.takeIf { it.isNotBlank() }
        )
        return storeRepository.save(store).toDto()
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
            isActive = request.isActive ?: store.isActive
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
}
