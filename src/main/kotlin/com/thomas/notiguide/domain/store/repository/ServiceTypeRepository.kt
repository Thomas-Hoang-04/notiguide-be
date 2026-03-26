package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.ServiceType
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface ServiceTypeRepository : CoroutineCrudRepository<ServiceType, UUID> {
    fun findByStoreId(storeId: UUID): Flow<ServiceType>
    fun findByStoreIdAndIsActive(storeId: UUID, isActive: Boolean): Flow<ServiceType>
    suspend fun findByStoreIdAndPrefix(storeId: UUID, prefix: String): ServiceType?
}
