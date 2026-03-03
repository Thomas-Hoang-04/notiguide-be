package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.putAllAndAwait
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class RedisTicketRepository(
    private val redis: ReactiveRedisTemplate<String, Any>
) {

    suspend fun createTicket(storeId: UUID, ticketId: UUID, number: String): Boolean {
        val key = RedisKeyManager.ticket(storeId, ticketId)
        redis.opsForHash<String, String>()
            .putAllAndAwait(key, mapOf(
                "store_id" to storeId.toString(),
                "number" to number,
                "status" to "WAITING",
                "issued_at" to Instant.now().epochSecond.toString()
            ))
        return redis.expire(key, RedisTTLPolicy.TICKET_WAITING).awaitSingle()
    }

    suspend fun markCalled(storeId: UUID, ticketId: UUID, counterId: String?): Boolean {
        val key = RedisKeyManager.ticket(storeId, ticketId)
        redis.opsForHash<String, String>()
            .putAllAndAwait(key, buildMap {
                put("status", "CALLED")
                put("called_at", Instant.now().epochSecond.toString())
                if (counterId != null) put("counter_id", counterId)
            })
        return redis.expire(key, RedisTTLPolicy.TICKET_CALLED).awaitSingle()
    }

    // TODO: Service layer must call getTicket() BEFORE this to read issued_at/called_at for wait_duration_seconds analytics
    suspend fun markServed(storeId: UUID, ticketId: UUID): Boolean =
        redis.delete(RedisKeyManager.ticket(storeId, ticketId)).awaitSingle() > 0

    // TODO: Service layer must call getTicket() BEFORE this to read issued_at/called_at for wait_duration_seconds analytics
    suspend fun markCancelled(storeId: UUID, ticketId: UUID): Boolean =
        redis.delete(RedisKeyManager.ticket(storeId, ticketId)).awaitSingle() > 0

    suspend fun getTicket(storeId: UUID, ticketId: UUID): Map<String, String> =
        redis.opsForHash<String, String>()
            .entries(RedisKeyManager.ticket(storeId, ticketId))
            .collectMap({ it.key }, { it.value })
            .awaitSingle()

    suspend fun getStatus(storeId: UUID, ticketId: UUID): String? =
        redis.opsForHash<String, String>()
            .getAndAwait(RedisKeyManager.ticket(storeId, ticketId), "status")

    suspend fun exists(storeId: UUID, ticketId: UUID): Boolean =
        redis.hasKey(RedisKeyManager.ticket(storeId, ticketId)).awaitSingle()
}
