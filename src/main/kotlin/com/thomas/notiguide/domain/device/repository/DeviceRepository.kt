package com.thomas.notiguide.domain.device.repository

import com.thomas.notiguide.domain.device.entity.Device
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DeviceRepository : CoroutineCrudRepository<Device, UUID> {

    @Query("SELECT * FROM device WHERE public_key_der = :publicKeyDer LIMIT 1")
    suspend fun findByPublicKeyDer(publicKeyDer: ByteArray): Device?

    @Query("SELECT EXISTS(SELECT 1 FROM device WHERE public_id = :publicId)")
    suspend fun existsByPublicId(publicId: String): Boolean

    @Query(
        """
        SELECT COUNT(*)
        FROM device
        WHERE kind = 'TRANSMITTER_HUB'
          AND store_id = :storeId
          AND status NOT IN ('DECOMMISSIONED', 'REJECTED')
          AND public_key_der IS DISTINCT FROM :publicKeyDer
        """
    )
    suspend fun countRegisteredHubsByStoreExcludingPublicKey(storeId: UUID, publicKeyDer: ByteArray): Long

    @Suppress("unused")
    @Query(
        """
        SELECT COUNT(*)
        FROM device
        WHERE kind = 'TRANSMITTER_HUB'
          AND store_id = :storeId
          AND status NOT IN ('DECOMMISSIONED', 'REJECTED')
        """
    )
    suspend fun countRegisteredHubsByStore(storeId: UUID): Long

    @Suppress("unused")
    fun findByStoreId(storeId: UUID): Flow<Device>
}
