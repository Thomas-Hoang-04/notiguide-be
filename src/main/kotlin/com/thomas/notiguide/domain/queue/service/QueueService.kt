package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.domain.queue.dto.TicketDto
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.repository.RedisCounterRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.repository.RedisTicketRepository
import com.thomas.notiguide.domain.queue.types.CallNextResult
import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class QueueService(
    private val storeRepository: StoreRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val redisTicketRepository: RedisTicketRepository,
    private val redisCounterRepository: RedisCounterRepository,
    private val redis: ReactiveRedisTemplate<String, Any>
) {

    companion object {
        private val ISSUE_TICKET_SCRIPT = RedisScript.of(
            """
            local queueKey = KEYS[1]
            local ticketKey = KEYS[2]
            local ticketId = ARGV[1]
            local score = ARGV[2]
            local storeId = ARGV[3]
            local number = ARGV[4]
            local issuedAt = ARGV[5]
            local ttlSeconds = ARGV[6]

            redis.call('ZADD', queueKey, score, ticketId)
            redis.call('HSET', ticketKey, 'store_id', storeId, 'number', number, 'status', 'WAITING', 'issued_at', issuedAt)
            redis.call('EXPIRE', ticketKey, ttlSeconds)

            return 1
            """.trimIndent(),
            Long::class.java
        )

        private val CALL_NEXT_SCRIPT = RedisScript.of(
            """
            local queueKey = KEYS[1]
            local servingKey = KEYS[2]
            local calledTtlSeconds = ARGV[1]
            local ticketKeyPrefix = ARGV[2]
            local counterId = ARGV[3]
            local calledAt = ARGV[4]
            local maxIterations = 100

            for i = 1, maxIterations do
                local result = redis.call('ZPOPMIN', queueKey)
                if #result == 0 then
                    return {'QUEUE_EMPTY', ''}
                end

                local ticketId = result[1]
                local ticketKey = ticketKeyPrefix .. ticketId

                local exists = redis.call('EXISTS', ticketKey)
                if exists == 1 then
                    redis.call('SADD', servingKey, ticketId)
                    redis.call('HSET', ticketKey, 'status', 'CALLED', 'called_at', calledAt)
                    if counterId ~= '' then
                        redis.call('HSET', ticketKey, 'counter_id', counterId)
                    end
                    redis.call('EXPIRE', ticketKey, calledTtlSeconds)
                    return {'SUCCESS', ticketId}
                end
            end

            return {'QUEUE_EMPTY', ''}
            """.trimIndent(),
            List::class.java
        )
    }

    suspend fun issueTicket(storeId: UUID): TicketDto {
        val store = storeRepository.findById(storeId)
            ?: throw NotFoundException("Store", "id", storeId.toString())

        if (!store.isActive)
            throw HttpException(HttpStatus.BAD_REQUEST, "Store is not currently active")

        val ticketId = UUID.randomUUID()
        val number = redisCounterRepository.getNextNumber(storeId)
        val now = Instant.now()

        val queueKey = RedisKeyManager.queue(storeId)
        val ticketKey = RedisKeyManager.ticket(storeId, ticketId)

        redis.execute(
            ISSUE_TICKET_SCRIPT,
            listOf(queueKey, ticketKey),
            listOf(
                ticketId.toString(),
                now.toEpochMilli().toString(),
                storeId.toString(),
                number.toString(),
                now.epochSecond.toString(),
                RedisTTLPolicy.TICKET_WAITING.toSeconds().toString()
            )
        ).next().awaitSingle()

        return TicketDto(
            id = ticketId,
            number = number.toString(),
            status = "WAITING",
            issuedAt = now,
            calledAt = null,
            position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)
        )
    }

    suspend fun getTicketStatus(storeId: UUID, ticketId: UUID): TicketStatusResponse {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        val position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)

        return TicketStatusResponse(
            status = ticket["status"] ?: "UNKNOWN",
            positionInQueue = position
        )
    }

    suspend fun callNext(storeId: UUID, counterId: String?): CallNextResult {
        val queueKey = RedisKeyManager.queue(storeId)
        val servingKey = RedisKeyManager.serving(storeId)
        val ticketKeyPrefix = "ticket:$storeId:"

        val result = redis.execute(
            CALL_NEXT_SCRIPT,
            listOf(queueKey, servingKey),
            listOf(
                RedisTTLPolicy.TICKET_CALLED.toSeconds().toString(),
                ticketKeyPrefix,
                counterId ?: "",
                Instant.now().epochSecond.toString()
            )
        ).next().awaitSingleOrNull()

        if (result.isNullOrEmpty() || result[0] == "QUEUE_EMPTY")
            return CallNextResult.QueueEmpty

        val ticketIdStr = result[1] as? String ?: return CallNextResult.QueueEmpty
        val ticketId = UUID.fromString(ticketIdStr)

        val ticketData = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticketData.isEmpty()) return CallNextResult.GhostTicketSkipped

        return CallNextResult.Success(
            TicketDto(
                id = ticketId,
                number = ticketData["number"] ?: "",
                status = ticketData["status"] ?: "CALLED",
                issuedAt = ticketData["issued_at"]?.toLongOrNull()?.let { Instant.ofEpochSecond(it) },
                calledAt = ticketData["called_at"]?.toLongOrNull()?.let { Instant.ofEpochSecond(it) },
                position = null
            )
        )
    }

    suspend fun callNextUntilSuccess(storeId: UUID, counterId: String?): CallNextResult {
        while (true) {
            when (val result = callNext(storeId, counterId)) {
                is CallNextResult.GhostTicketSkipped -> continue
                else -> return result
            }
        }
    }

    suspend fun serveTicket(storeId: UUID, ticketId: UUID) {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        // TODO: emit analytics event with wait_duration_seconds
        redisTicketRepository.markServed(storeId, ticketId)
        redisQueueRepository.removeFromServing(storeId, ticketId)
    }

    suspend fun cancelTicket(storeId: UUID, ticketId: UUID) {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        val status = ticket["status"]
        // TODO: emit analytics event with wait_duration_seconds
        redisTicketRepository.markCancelled(storeId, ticketId)

        when (status) {
            "WAITING" -> redisQueueRepository.removeFromQueue(storeId, ticketId)
            "CALLED" -> redisQueueRepository.removeFromServing(storeId, ticketId)
        }
    }

    suspend fun cleanupServingSet(storeId: UUID): Int {
        var cleaned = 0
        val servingTickets = redisQueueRepository.getServingTickets(storeId).toList()
        for (ticketIdStr in servingTickets) {
            val ticketId = try { UUID.fromString(ticketIdStr) } catch (_: IllegalArgumentException) { continue }
            if (!redisTicketRepository.exists(storeId, ticketId)) {
                redisQueueRepository.removeFromServing(storeId, ticketId)
                cleaned++
            }
        }
        return cleaned
    }

    suspend fun clearStoreData(storeId: UUID) {
        val queueKey = RedisKeyManager.queue(storeId)
        val servingKey = RedisKeyManager.serving(storeId)
        val counterKey = RedisKeyManager.counter(storeId)

        redis.delete(queueKey).awaitSingle()
        redis.delete(servingKey).awaitSingle()
        redis.delete(counterKey).awaitSingle()

        redis.scan(ScanOptions.scanOptions().match("ticket:$storeId:*").count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()
    }

    suspend fun getTicketDto(storeId: UUID, ticketId: UUID): TicketDto {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        val position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)

        return TicketDto(
            id = ticketId,
            number = ticket["number"] ?: "",
            status = ticket["status"] ?: "UNKNOWN",
            issuedAt = ticket["issued_at"]?.toLongOrNull()?.let { Instant.ofEpochSecond(it) },
            calledAt = ticket["called_at"]?.toLongOrNull()?.let { Instant.ofEpochSecond(it) },
            position = position
        )
    }
}
