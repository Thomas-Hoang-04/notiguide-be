package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.flow.Flow
import org.springframework.data.redis.core.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RedisQueueRepository(
    private val redis: ReactiveRedisTemplate<String, String>
) {

    suspend fun removeFromQueue(storeId: UUID, ticketId: UUID): Long =
        redis.opsForZSet()
            .removeAndAwait(RedisKeyManager.queue(storeId), ticketId.toString())

    suspend fun getQueueSize(storeId: UUID): Long =
        redis.opsForZSet()
            .sizeAndAwait(RedisKeyManager.queue(storeId))

    suspend fun getQueuePosition(storeId: UUID, ticketId: UUID): Long? =
        redis.opsForZSet()
            .rankAndAwait(RedisKeyManager.queue(storeId), ticketId.toString())

    suspend fun removeFromServing(storeId: UUID, ticketId: UUID): Long =
        redis.opsForSet()
            .removeAndAwait(RedisKeyManager.serving(storeId), ticketId.toString())

    fun getServingTickets(storeId: UUID): Flow<String> =
        redis.opsForSet()
            .membersAsFlow(RedisKeyManager.serving(storeId))
}
