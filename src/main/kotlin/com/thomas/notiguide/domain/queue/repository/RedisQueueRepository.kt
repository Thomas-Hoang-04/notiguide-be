package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.membersAsFlow
import org.springframework.data.redis.core.rankAndAwait
import org.springframework.data.redis.core.removeAndAwait
import org.springframework.data.redis.core.sizeAndAwait
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

    suspend fun getServingCount(storeId: UUID): Long =
        redis.opsForSet()
            .sizeAndAwait(RedisKeyManager.serving(storeId))

    fun getServingTickets(storeId: UUID): Flow<String> =
        redis.opsForSet()
            .membersAsFlow(RedisKeyManager.serving(storeId))

    suspend fun getWaitingTicketIds(storeId: UUID): List<String> =
        redis.opsForZSet()
            .range(RedisKeyManager.queue(storeId), Range.unbounded())
            .collectList()
            .awaitSingle()
}
