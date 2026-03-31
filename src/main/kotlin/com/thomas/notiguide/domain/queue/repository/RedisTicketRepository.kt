package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.queue.types.TicketStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RedisTicketRepository(
    private val redis: ReactiveRedisTemplate<String, String>
) {

    suspend fun markServed(storeId: UUID, ticketId: UUID): Boolean =
        markTerminalStatus(storeId, ticketId, TicketStatus.SERVED)

    suspend fun markCancelled(storeId: UUID, ticketId: UUID): Boolean =
        markTerminalStatus(storeId, ticketId, TicketStatus.CANCELLED)

    suspend fun getTicket(storeId: UUID, ticketId: UUID): Map<String, String> =
        redis.opsForHash<String, String>()
            .entries(RedisKeyManager.ticket(storeId, ticketId))
            .collectMap({ it.key }, { it.value })
            .awaitSingle()

    suspend fun exists(storeId: UUID, ticketId: UUID): Boolean =
        redis.hasKey(RedisKeyManager.ticket(storeId, ticketId)).awaitSingle()

    suspend fun markSkipped(storeId: UUID, ticketId: UUID): Boolean =
        markTerminalStatus(storeId, ticketId, TicketStatus.SKIPPED)

    private suspend fun markTerminalStatus(
        storeId: UUID,
        ticketId: UUID,
        status: TicketStatus
    ): Boolean {
        val ticketKey = RedisKeyManager.ticket(storeId, ticketId)
        if (!redis.hasKey(ticketKey).awaitSingle()) {
            return false
        }

        redis.opsForHash<String, String>()
            .put(ticketKey, "status", status.name)
            .awaitSingle()
        redis.expire(ticketKey, RedisTTLPolicy.TICKET_TERMINAL).awaitSingle()

        return true
    }
}
