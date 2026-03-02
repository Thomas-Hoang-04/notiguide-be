package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class RedisQueueRepository(
    private val redis: ReactiveRedisTemplate<String, Any>
) {

    suspend fun addToQueue(storeId: UUID, ticketId: UUID): Boolean =
        redis.opsForZSet()
            .add(RedisKeyManager.queue(storeId), ticketId.toString(), Instant.now().toEpochMilli().toDouble())
            .awaitSingle()

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
            .remove(RedisKeyManager.queue(storeId), ticketId.toString())
            .awaitSingle()

    suspend fun getQueueSize(storeId: UUID): Long =
        redis.opsForZSet()
            .size(RedisKeyManager.queue(storeId))
            .awaitSingle()

    suspend fun getQueuePosition(storeId: UUID, ticketId: UUID): Long? =
        redis.opsForZSet()
            .rank(RedisKeyManager.queue(storeId), ticketId.toString())
            .awaitSingleOrNull()

    suspend fun addToServing(storeId: UUID, ticketId: UUID): Long =
        redis.opsForSet()
            .add(RedisKeyManager.serving(storeId), ticketId.toString())
            .awaitSingle()

    suspend fun removeFromServing(storeId: UUID, ticketId: UUID): Long =
        redis.opsForSet()
            .remove(RedisKeyManager.serving(storeId), ticketId.toString())
            .awaitSingle()

    fun getServingTickets(storeId: UUID): Flow<Any> =
        redis.opsForSet()
            .members(RedisKeyManager.serving(storeId))
            .asFlow()
}
