package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RedisTicketRepository(
    private val redis: ReactiveRedisTemplate<String, String>
) {

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

    suspend fun exists(storeId: UUID, ticketId: UUID): Boolean =
        redis.hasKey(RedisKeyManager.ticket(storeId, ticketId)).awaitSingle()
}
