package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.store.dto.ServiceTypeDto
import com.thomas.notiguide.domain.store.dto.ServiceTypePublicDto
import com.thomas.notiguide.domain.store.entity.ServiceType
import com.thomas.notiguide.domain.store.repository.ServiceTypeRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.request.CreateServiceTypeRequest
import com.thomas.notiguide.domain.store.request.UpdateServiceTypeRequest
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ServiceTypeService(
    private val serviceTypeRepository: ServiceTypeRepository,
    private val storeRepository: StoreRepository
) {

    @Transactional(readOnly = true)
    suspend fun listServiceTypes(storeId: UUID): List<ServiceTypeDto> {
        storeRepository.findById(storeId) ?: throw NotFoundException("Store", "id", storeId.toString())
        return serviceTypeRepository.findByStoreId(storeId).toList().map { it.toDto() }
    }

    @Transactional(readOnly = true)
    suspend fun listActiveServiceTypes(storeId: UUID): List<ServiceTypePublicDto> {
        storeRepository.findById(storeId) ?: throw NotFoundException("Store", "id", storeId.toString())
        return serviceTypeRepository.findByStoreIdAndIsActive(storeId, true)
            .toList()
            .map { it.toPublicDto() }
    }

    @Transactional
    suspend fun createServiceType(storeId: UUID, request: CreateServiceTypeRequest): ServiceTypeDto {
        storeRepository.findById(storeId) ?: throw NotFoundException("Store", "id", storeId.toString())

        val existing = serviceTypeRepository.findByStoreIdAndPrefix(storeId, request.prefix)
        if (existing != null) throw ConflictException("Prefix '${request.prefix}' already exists for this store")

        val serviceType = ServiceType(
            storeId = storeId,
            name = request.name,
            prefix = request.prefix
        )
        return serviceTypeRepository.save(serviceType).toDto()
    }

    @Transactional
    suspend fun updateServiceType(storeId: UUID, id: UUID, request: UpdateServiceTypeRequest): ServiceTypeDto {
        val serviceType = serviceTypeRepository.findById(id)
            ?: throw NotFoundException("ServiceType", "id", id.toString())

        if (serviceType.storeId != storeId) throw NotFoundException("ServiceType", "id", id.toString())

        if (request.prefix != null && request.prefix != serviceType.prefix) {
            val conflict = serviceTypeRepository.findByStoreIdAndPrefix(storeId, request.prefix)
            if (conflict != null && conflict.id != id) {
                throw ConflictException("Prefix '${request.prefix}' already exists for this store")
            }
        }

        val updated = serviceType.copy(
            name = request.name ?: serviceType.name,
            prefix = request.prefix ?: serviceType.prefix,
            isActive = request.isActive ?: serviceType.isActive
        )
        return serviceTypeRepository.save(updated).toDto()
    }

    @Transactional
    suspend fun deleteServiceType(storeId: UUID, id: UUID) {
        val serviceType = serviceTypeRepository.findById(id)
            ?: throw NotFoundException("ServiceType", "id", id.toString())
        if (serviceType.storeId != storeId) throw NotFoundException("ServiceType", "id", id.toString())

        val activeCount = serviceTypeRepository.findByStoreIdAndIsActive(storeId, true).toList().size
        if (activeCount <= 1 && serviceType.isActive) {
            throw ConflictException("Cannot delete the last active service type")
        }

        serviceTypeRepository.delete(serviceType)
    }

    private fun ServiceType.toDto() = ServiceTypeDto(
        id = id.toString(),
        storeId = storeId.toString(),
        name = name,
        prefix = prefix,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ServiceType.toPublicDto() = ServiceTypePublicDto(
        id = id.toString(),
        name = name,
        prefix = prefix
    )
}
