package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreRequest
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class StoreService(
    private val storeRepository: StoreRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val queueService: QueueService
) {

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
            address = request.address
        )
        return storeRepository.save(store).toDto()
    }

    @Transactional
    suspend fun updateStore(id: UUID, request: UpdateStoreRequest): StoreDto {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())

        val updated = store.copy(
            name = request.name ?: store.name,
            address = request.address ?: store.address,
            isActive = request.isActive ?: store.isActive
        )
        return storeRepository.save(updated).toDto()
    }

    @Transactional
    suspend fun deleteStore(id: UUID) {
        val store = storeRepository.findById(id)
            ?: throw NotFoundException("Store", "id", id.toString())

        val queueSize = redisQueueRepository.getQueueSize(id)
        val servingTickets = redisQueueRepository.getServingTickets(id).toList()
        if (queueSize > 0 || servingTickets.isNotEmpty())
            throw ConflictException("Store has active queue tickets. Drain or clear the queue before deleting.")

        queueService.clearStoreData(id)
        storeRepository.delete(store)
    }
}
