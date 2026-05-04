package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.TransmitterActiveRecord
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class TransmitterElectionService(
    private val deviceRepository: DeviceRepository,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val properties: DeviceTransmitterProperties
) {

    suspend fun electActive(storeId: UUID): Device? {
        val cacheKey = RedisKeyManager.storeTransmitterActive(storeId)
        val cached = readCachedRecord(cacheKey)
        if (cached != null) {
            val device = deviceRepository.findById(cached.deviceId)
            if (device != null && isEligibleCachedHub(device, storeId) && isLive(device.id!!)) {
                return device
            }
            redis.delete(cacheKey).awaitSingleOrNull()
        }

        val elected = deviceRepository.findActiveHubsByStore(storeId).toList()
            .firstOrNull { it.id != null && isLive(it.id) }
            ?: return null

        val payload = objectMapper.writeValueAsString(
            TransmitterActiveRecord(
                deviceId = elected.id!!,
                electedAt = OffsetDateTime.now(ZoneOffset.UTC)
            )
        )
        redis.opsForValue()
            .set(cacheKey, payload, Duration.ofSeconds(properties.activeCacheSeconds))
            .awaitSingle()
        return elected
    }

    private suspend fun readCachedRecord(cacheKey: String): TransmitterActiveRecord? {
        val payload = redis.opsForValue().get(cacheKey).awaitSingleOrNull() ?: return null
        return runCatching {
            objectMapper.readValue(payload, TransmitterActiveRecord::class.java)
        }.getOrNull()
    }

    private suspend fun isLive(deviceId: UUID): Boolean =
        redis.hasKey(RedisKeyManager.deviceHubAlive(deviceId)).awaitSingle()

    private fun isEligibleCachedHub(
        device: Device,
        storeId: UUID
    ): Boolean =
        device.kind.isHub() &&
            device.status == DeviceStatus.ACTIVE &&
            device.storeId == storeId &&
            device.publicId != null
}
