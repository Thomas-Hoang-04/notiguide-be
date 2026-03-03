package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class RedisQueueRepository(
    private val redis: ReactiveRedisTemplate<String, Any>
) {

    suspend fun addToQueue(storeId: UUID, ticketId: UUID): Boolean =
        redis.opsForZSet()
            .addAndAwait(RedisKeyManager.queue(storeId), ticketId.toString(), Instant.now().toEpochMilli().toDouble())

    suspend fun peekNext(storeId: UUID): String? =
        redis.opsForZSet()
            .range(RedisKeyManager.queue(storeId), Range.closed(0L, 0L))
            .next()
            .awaitSingleOrNull()
            ?.toString()

    suspend fun popNext(storeId: UUID): String? =
        redis.opsForZSet()
            .popMin(RedisKeyManager.queue(storeId))
            .awaitSingleOrNull()
            ?.value
            ?.toString()

    suspend fun removeFromQueue(storeId: UUID, ticketId: UUID): Long =
        redis.opsForZSet()
            .removeAndAwait(RedisKeyManager.queue(storeId), ticketId.toString())

    suspend fun getQueueSize(storeId: UUID): Long =
        redis.opsForZSet()
            .sizeAndAwait(RedisKeyManager.queue(storeId))

    suspend fun getQueuePosition(storeId: UUID, ticketId: UUID): Long? =
        redis.opsForZSet()
            .rankAndAwait(RedisKeyManager.queue(storeId), ticketId.toString())

    suspend fun addToServing(storeId: UUID, ticketId: UUID): Long =
        redis.opsForSet()
            .addAndAwait(RedisKeyManager.serving(storeId), ticketId.toString())

    suspend fun removeFromServing(storeId: UUID, ticketId: UUID): Long =
        redis.opsForSet()
            .removeAndAwait(RedisKeyManager.serving(storeId), ticketId.toString())

    fun getServingTickets(storeId: UUID): Flow<String> =
        redis.opsForSet()
            .membersAsFlow(RedisKeyManager.serving(storeId))
            .map { it.toString() }
}
