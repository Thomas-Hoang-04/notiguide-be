package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.firebase.FcmNotificationService
import com.thomas.notiguide.core.mqtt.MqttPublisher
import com.thomas.notiguide.core.mqtt.MqttPublisher.QueueEvent
import com.thomas.notiguide.core.mqtt.MqttPublisher.QueueEventType
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.core.sse.QueueEventBroadcaster
import com.thomas.notiguide.core.sse.QueueSseEvent
import com.thomas.notiguide.domain.analytics.service.AnalyticsEventService
import com.thomas.notiguide.domain.analytics.service.AnalyticsQueryService
import com.thomas.notiguide.domain.queue.dto.TicketDto
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.repository.RedisCounterRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.repository.RedisTicketRepository
import com.thomas.notiguide.domain.queue.types.CallNextResult
import com.thomas.notiguide.domain.queue.types.QueueState
import com.thomas.notiguide.domain.queue.types.TicketStatus
import com.thomas.notiguide.domain.store.entity.StoreSettings
import com.thomas.notiguide.domain.store.repository.ServiceTypeRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.repository.StoreSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*

@Service
class QueueService(
    private val storeRepository: StoreRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val redisTicketRepository: RedisTicketRepository,
    private val redisCounterRepository: RedisCounterRepository,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val mqttPublisher: MqttPublisher? = null,
    private val fcmNotificationService: FcmNotificationService? = null,
    private val analyticsEventService: AnalyticsEventService? = null,
    private val analyticsQueryService: AnalyticsQueryService? = null,
    private val queueEventBroadcaster: QueueEventBroadcaster,
    private val storeSettingsRepository: StoreSettingsRepository,
    private val serviceTypeRepository: ServiceTypeRepository
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val CALL_NEXT_MAX_RETRIES = 10

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
            redis.call('HSET', ticketKey, 'store_id', storeId, 'number', number, 'status', '${TicketStatus.WAITING.name}', 'issued_at', issuedAt)
            redis.call('EXPIRE', ticketKey, ttlSeconds)

            return 1
            """.trimIndent(),
            Long::class.java
        )

        private val CALL_SPECIFIC_SCRIPT = RedisScript.of(
            """
            local queueKey = KEYS[1]
            local servingKey = KEYS[2]
            local ticketKey = KEYS[3]
            local ticketId = ARGV[1]
            local calledTtlSeconds = ARGV[2]
            local counterId = ARGV[3]
            local calledAt = ARGV[4]

            local removed = redis.call('ZREM', queueKey, ticketId)
            if removed == 0 then
                return 'NOT_IN_QUEUE'
            end

            local exists = redis.call('EXISTS', ticketKey)
            if exists == 0 then
                return 'NOT_FOUND'
            end

            redis.call('SADD', servingKey, ticketId)
            redis.call('HSET', ticketKey, 'status', '${TicketStatus.CALLED.name}', 'called_at', calledAt)
            if counterId ~= '' then
                redis.call('HSET', ticketKey, 'counter_id', counterId)
            end
            redis.call('EXPIRE', ticketKey, calledTtlSeconds)
            return 'SUCCESS'
            """.trimIndent(),
            String::class.java
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
                    redis.call('HSET', ticketKey, 'status', '${TicketStatus.CALLED.name}', 'called_at', calledAt)
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

        private val REQUEUE_TICKET_SCRIPT = RedisScript.of(
            """
            local servingKey = KEYS[1]
            local queueKey = KEYS[2]
            local ticketKey = KEYS[3]
            local ticketId = ARGV[1]
            local offset = tonumber(ARGV[2])
            local waitingTtlSeconds = ARGV[3]
            local nowMs = ARGV[4]

            redis.call('SREM', servingKey, ticketId)

            local range = redis.call('ZRANGE', queueKey, 0, offset - 1, 'WITHSCORES')
            local insertScore
            if #range >= offset * 2 then
                insertScore = tonumber(range[offset * 2]) + 0.001
            else
                local last = redis.call('ZRANGE', queueKey, -1, -1, 'WITHSCORES')
                if #last >= 2 then
                    insertScore = tonumber(last[2]) + 0.001
                else
                    insertScore = tonumber(nowMs)
                end
            end

            redis.call('ZADD', queueKey, insertScore, ticketId)
            redis.call('HSET', ticketKey, 'status', 'REQUEUED')
            redis.call('EXPIRE', ticketKey, waitingTtlSeconds)

            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    suspend fun setQueueState(storeId: UUID, state: QueueState) {
        redis.opsForValue()
            .set(RedisKeyManager.queueState(storeId), state.name)
            .awaitSingle()
    }

    suspend fun getQueueState(storeId: UUID): QueueState {
        val raw = redis.opsForValue()
            .get(RedisKeyManager.queueState(storeId))
            .awaitSingleOrNull()
        return QueueState.from(raw)
    }

    suspend fun issueTicket(storeId: UUID, serviceTypeId: UUID? = null): TicketDto {
        val store = storeRepository.findById(storeId)
            ?: throw NotFoundException("Store", "id", storeId.toString())

        if (!store.isActive)
            throw HttpException(HttpStatus.BAD_REQUEST, "Store is not currently active")

        val queueState = getQueueState(storeId)
        if (queueState == QueueState.PAUSED) {
            throw ConflictException("Queue is paused")
        }

        val settings = storeSettingsRepository.findById(storeId)
        if (settings != null && settings.maxQueueSize > 0) {
            val currentSize = redisQueueRepository.getQueueSize(storeId)
            if (currentSize >= settings.maxQueueSize) {
                throw ConflictException("Queue is full")
            }
        }

        val resolvedServiceType = if (serviceTypeId != null) {
            serviceTypeRepository.findById(serviceTypeId)
                ?: throw NotFoundException("ServiceType", "id", serviceTypeId.toString())
        } else {
            serviceTypeRepository.findByStoreIdAndIsActive(storeId, true).first()
        }

        val ticketId = UUID.randomUUID()
        val number = redisCounterRepository.getNextNumber(storeId)
        val ticketNumber = "${resolvedServiceType.prefix}-$number"
        val now = Instant.now()

        val queueKey = RedisKeyManager.queue(storeId, resolvedServiceType.id!!)
        val ticketKey = RedisKeyManager.ticket(storeId, ticketId)

        redis.execute(
            ISSUE_TICKET_SCRIPT,
            listOf(queueKey, ticketKey),
            listOf(
                ticketId.toString(),
                now.toEpochMilli().toString(),
                storeId.toString(),
                ticketNumber,
                now.toEpochMilli().toString(),
                RedisTTLPolicy.TICKET_WAITING.toSeconds().toString()
            )
        ).next().awaitSingle()

        // Also add to the global queue for backward compatibility
        redis.opsForZSet()
            .add(RedisKeyManager.queue(storeId), ticketId.toString(), now.toEpochMilli().toDouble())
            .awaitSingle()

        // Store service type on ticket hash for routing
        redis.opsForHash<String, String>()
            .put(ticketKey, "service_type_id", resolvedServiceType.id.toString())
            .awaitSingle()

        log.info("Ticket issued: store={} ticket={} number={} serviceType={}", storeId, ticketId, ticketNumber, resolvedServiceType.name)

        analyticsEventService?.recordTicketIssued(storeId, ticketId, ticketNumber)

        val issuedEvent = QueueSseEvent(
            type = "TICKET_ISSUED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketNumber
        )
        mqttPublisher?.publishQueueEvent(storeId, QueueEvent(
            type = QueueEventType.TICKET_ISSUED,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketNumber
        ))
        queueEventBroadcaster.broadcast(issuedEvent)

        return TicketDto(
            id = ticketId,
            number = ticketNumber,
            status = TicketStatus.WAITING,
            issuedAt = now,
            calledAt = null,
            position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)
        )
    }

    suspend fun getTicketStatus(storeId: UUID, ticketId: UUID): TicketStatusResponse {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        val status = TicketStatus.from(ticket["status"])
        val position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)

        val estimatedWait = if (status == TicketStatus.WAITING && position != null) {
            analyticsQueryService?.getEstimatedWaitMinutes(storeId, position)
        } else null

        return TicketStatusResponse(
            status = status,
            positionInQueue = position,
            estimatedWaitTime = estimatedWait
        )
    }

    suspend fun callNext(storeId: UUID, counterId: String?, serviceTypeId: UUID? = null): CallNextResult {
        val queueKey = if (serviceTypeId != null) {
            RedisKeyManager.queue(storeId, serviceTypeId)
        } else {
            RedisKeyManager.queue(storeId)
        }
        val servingKey = RedisKeyManager.serving(storeId)
        val ticketKeyPrefix = RedisKeyManager.ticketKeyPrefix(storeId)

        val result = redis.execute(
            CALL_NEXT_SCRIPT,
            listOf(queueKey, servingKey),
            listOf(
                RedisTTLPolicy.TICKET_CALLED.toSeconds().toString(),
                ticketKeyPrefix,
                counterId ?: "",
                Instant.now().toEpochMilli().toString()
            )
        ).next().awaitSingleOrNull()

        if (result.isNullOrEmpty())
            return CallNextResult.QueueEmpty

        val scriptStatus = result[0]?.toString()
        if (scriptStatus == "QUEUE_EMPTY")
            return CallNextResult.QueueEmpty

        val ticketIdStr = result.getOrNull(1)?.toString() ?: return CallNextResult.QueueEmpty
        val ticketId = runCatching { UUID.fromString(ticketIdStr) }.getOrElse { return CallNextResult.QueueEmpty }

        val ticketData = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticketData.isEmpty()) {
            redisQueueRepository.removeFromServing(storeId, ticketId)
            return CallNextResult.GhostTicketSkipped
        }

        // Cross-queue cleanup: remove from the counterpart queue
        val ticketServiceTypeId = ticketData["service_type_id"]
        if (serviceTypeId != null) {
            // Called from service-type queue → remove from global queue
            redis.opsForZSet().remove(RedisKeyManager.queue(storeId), ticketId.toString()).awaitSingleOrNull()
        } else if (ticketServiceTypeId != null) {
            // Called from global queue → remove from service-type queue
            val stId = runCatching { UUID.fromString(ticketServiceTypeId) }.getOrNull()
            if (stId != null) {
                redis.opsForZSet().remove(RedisKeyManager.queue(storeId, stId), ticketId.toString()).awaitSingleOrNull()
            }
        }

        log.info("Ticket called: store={} ticket={} counter={}", storeId, ticketId, counterId)

        val issuedAt = parseStoredTimestamp(ticketData["issued_at"])
        if (issuedAt != null) {
            analyticsEventService?.recordTicketCalled(
                storeId, ticketId, ticketData["number"] ?: "", counterId, issuedAt
            )
        }

        val calledEvent = QueueSseEvent(
            type = "TICKET_CALLED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        )
        mqttPublisher?.publishQueueEvent(storeId, QueueEvent(
            type = QueueEventType.TICKET_CALLED,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        ))
        queueEventBroadcaster.broadcast(calledEvent)

        fcmNotificationService?.sendTicketCalledNotification(
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        )

        setGraceExpiryIfNeeded(storeId, ticketId)
        sendProactiveAlerts(storeId)

        return CallNextResult.Success(
            TicketDto(
                id = ticketId,
                number = ticketData["number"] ?: "",
                status = TicketStatus.from(ticketData["status"]).let {
                    if (it == TicketStatus.UNKNOWN) TicketStatus.CALLED else it
                },
                issuedAt = parseStoredTimestamp(ticketData["issued_at"]),
                calledAt = parseStoredTimestamp(ticketData["called_at"]),
                position = null
            )
        )
    }

    suspend fun callNextUntilSuccess(storeId: UUID, counterId: String?, serviceTypeId: UUID? = null): CallNextResult {
        repeat(CALL_NEXT_MAX_RETRIES) {
            when (val result = callNext(storeId, counterId, serviceTypeId)) {
                is CallNextResult.GhostTicketSkipped -> Unit
                else -> return result
            }
        }
        return CallNextResult.QueueEmpty
    }

    suspend fun callSpecificTicket(storeId: UUID, ticketId: UUID, counterId: String?): CallNextResult {
        val queueKey = RedisKeyManager.queue(storeId)
        val servingKey = RedisKeyManager.serving(storeId)
        val ticketKey = RedisKeyManager.ticket(storeId, ticketId)

        val result = redis.execute(
            CALL_SPECIFIC_SCRIPT,
            listOf(queueKey, servingKey, ticketKey),
            listOf(
                ticketId.toString(),
                RedisTTLPolicy.TICKET_CALLED.toSeconds().toString(),
                counterId ?: "",
                Instant.now().toEpochMilli().toString()
            )
        ).next().awaitSingleOrNull() ?: return CallNextResult.QueueEmpty

        if (result != "SUCCESS") {
            return if (result == "NOT_FOUND") CallNextResult.GhostTicketSkipped else CallNextResult.QueueEmpty
        }

        val ticketData = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticketData.isEmpty()) {
            redisQueueRepository.removeFromServing(storeId, ticketId)
            return CallNextResult.GhostTicketSkipped
        }

        // Cross-queue cleanup: also remove from service-type queue
        val ticketServiceTypeId = ticketData["service_type_id"]
        if (ticketServiceTypeId != null) {
            val stId = runCatching { UUID.fromString(ticketServiceTypeId) }.getOrNull()
            if (stId != null) {
                redis.opsForZSet().remove(RedisKeyManager.queue(storeId, stId), ticketId.toString()).awaitSingleOrNull()
            }
        }

        log.info("Ticket jump-called: store={} ticket={} counter={}", storeId, ticketId, counterId)

        val issuedAt = parseStoredTimestamp(ticketData["issued_at"])
        if (issuedAt != null) {
            analyticsEventService?.recordTicketCalled(
                storeId, ticketId, ticketData["number"] ?: "", counterId, issuedAt
            )
        }

        val calledEvent = QueueSseEvent(
            type = "TICKET_CALLED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        )
        mqttPublisher?.publishQueueEvent(storeId, QueueEvent(
            type = QueueEventType.TICKET_CALLED,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        ))
        queueEventBroadcaster.broadcast(calledEvent)

        fcmNotificationService?.sendTicketCalledNotification(
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"],
            counterId = counterId
        )

        setGraceExpiryIfNeeded(storeId, ticketId)
        sendProactiveAlerts(storeId)

        return CallNextResult.Success(
            TicketDto(
                id = ticketId,
                number = ticketData["number"] ?: "",
                status = TicketStatus.CALLED,
                issuedAt = parseStoredTimestamp(ticketData["issued_at"]),
                calledAt = parseStoredTimestamp(ticketData["called_at"]),
                position = null
            )
        )
    }

    suspend fun serveTicket(storeId: UUID, ticketId: UUID) {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty()) {
            redisQueueRepository.removeFromQueue(storeId, ticketId)
            redisQueueRepository.removeFromServing(storeId, ticketId)
            log.info("Ticket serve idempotent no-op: store={} ticket={}", storeId, ticketId)
            return
        }

        val status = TicketStatus.from(ticket["status"])
        val calledAt = parseStoredTimestamp(ticket["called_at"])
        analyticsEventService?.recordTicketCompleted(
            storeId, ticketId, ticket["number"] ?: "", calledAt
        )
        redisTicketRepository.markServed(storeId, ticketId)

        when (status) {
            TicketStatus.CALLED -> redisQueueRepository.removeFromServing(storeId, ticketId)
            else -> {
                redisQueueRepository.removeFromQueue(storeId, ticketId)
                redisQueueRepository.removeFromServing(storeId, ticketId)
            }
        }

        log.info("Ticket served: store={} ticket={} previous_status={}", storeId, ticketId, status)

        fcmNotificationService?.removeToken(storeId, ticketId)

        val servedEvent = QueueSseEvent(
            type = "TICKET_SERVED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticket["number"]
        )
        mqttPublisher?.publishQueueEvent(storeId, QueueEvent(
            type = QueueEventType.TICKET_SERVED,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticket["number"]
        ))
        queueEventBroadcaster.broadcast(servedEvent)

        redis.delete(RedisKeyManager.graceExpiry(storeId, ticketId)).awaitSingleOrNull()
    }

    suspend fun cancelTicket(storeId: UUID, ticketId: UUID) {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty()) {
            redisQueueRepository.removeFromQueue(storeId, ticketId)
            redisQueueRepository.removeFromServing(storeId, ticketId)
            log.info("Ticket cancel idempotent no-op: store={} ticket={}", storeId, ticketId)
            return
        }

        val status = TicketStatus.from(ticket["status"])
        val issuedAt = parseStoredTimestamp(ticket["issued_at"])
        analyticsEventService?.recordTicketCancelled(
            storeId, ticketId, ticket["number"] ?: "", issuedAt, status
        )
        redisTicketRepository.markCancelled(storeId, ticketId)

        when (status) {
            TicketStatus.WAITING -> redisQueueRepository.removeFromQueue(storeId, ticketId)
            TicketStatus.CALLED -> redisQueueRepository.removeFromServing(storeId, ticketId)
            else -> {
                redisQueueRepository.removeFromQueue(storeId, ticketId)
                redisQueueRepository.removeFromServing(storeId, ticketId)
            }
        }
        log.info("Ticket cancelled: store={} ticket={} previous_status={}", storeId, ticketId, status)

        fcmNotificationService?.removeToken(storeId, ticketId)

        val cancelledEvent = QueueSseEvent(
            type = "TICKET_CANCELLED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticket["number"]
        )
        mqttPublisher?.publishQueueEvent(storeId, QueueEvent(
            type = QueueEventType.TICKET_CANCELLED,
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticket["number"]
        ))
        queueEventBroadcaster.broadcast(cancelledEvent)

        redis.delete(RedisKeyManager.graceExpiry(storeId, ticketId)).awaitSingleOrNull()
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

    suspend fun getQueueSize(storeId: UUID): Long =
        redisQueueRepository.getQueueSize(storeId)

    suspend fun listWaitingTickets(storeId: UUID): List<TicketDto> {
        val ticketIds = redisQueueRepository.getWaitingTicketIds(storeId)
        return ticketIds.mapIndexedNotNull { index, idStr ->
            val ticketId = runCatching { UUID.fromString(idStr) }.getOrNull()
                ?: return@mapIndexedNotNull null
            val ticket = redisTicketRepository.getTicket(storeId, ticketId)
            if (ticket.isEmpty()) return@mapIndexedNotNull null
            TicketDto(
                id = ticketId,
                number = ticket["number"] ?: "",
                status = TicketStatus.from(ticket["status"]),
                issuedAt = parseStoredTimestamp(ticket["issued_at"]),
                calledAt = parseStoredTimestamp(ticket["called_at"]),
                position = (index + 1).toLong()
            )
        }
    }

    suspend fun clearStoreData(storeId: UUID) {
        val queueKey = RedisKeyManager.queue(storeId)
        val servingKey = RedisKeyManager.serving(storeId)

        redis.delete(queueKey).awaitSingle()
        redis.delete(servingKey).awaitSingle()

        // Delete queue state and cached settings
        redis.delete(RedisKeyManager.queueState(storeId)).awaitSingleOrNull()
        redis.delete(RedisKeyManager.storeSettings(storeId)).awaitSingleOrNull()
        redis.delete(RedisKeyManager.avgServiceDuration(storeId)).awaitSingleOrNull()

        // Scan-delete service-type queue and serving keys
        redis.scan(ScanOptions.scanOptions().match("store:$storeId:queue:*").count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()

        redis.scan(ScanOptions.scanOptions().match("store:$storeId:serving:*").count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()

        // Scan-delete counter keys (date suffix varies by timezone) and ticket keys
        redis.scan(ScanOptions.scanOptions().match(RedisKeyManager.counterPattern(storeId)).count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()

        redis.scan(ScanOptions.scanOptions().match(RedisKeyManager.ticketPattern(storeId)).count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()

        // Scan-delete grace expiry keys
        redis.scan(ScanOptions.scanOptions().match("grace:$storeId:*").count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()

        // Scan-delete FCM token keys
        redis.scan(ScanOptions.scanOptions().match(RedisKeyManager.fcmTokenPattern(storeId)).count(100).build())
            .flatMap { key -> redis.delete(key) }
            .collectList()
            .awaitSingle()
    }

    @Suppress("unused")
    suspend fun getTicketDto(storeId: UUID, ticketId: UUID): TicketDto {
        val ticket = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticket.isEmpty())
            throw NotFoundException("Ticket", "id", ticketId.toString())

        val position = redisQueueRepository.getQueuePosition(storeId, ticketId)?.plus(1)

        return TicketDto(
            id = ticketId,
            number = ticket["number"] ?: "",
            status = TicketStatus.from(ticket["status"]),
            issuedAt = parseStoredTimestamp(ticket["issued_at"]),
            calledAt = parseStoredTimestamp(ticket["called_at"]),
            position = position
        )
    }

    suspend fun handleNoShow(storeId: UUID, ticketId: UUID) {
        val settings = requireNoShowSettings(storeId)
        val ticketData = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticketData.isEmpty()) return

        val status = TicketStatus.from(ticketData["status"])
        if (status != TicketStatus.CALLED) return

        val requeueCount = ticketData["requeue_count"]?.toIntOrNull() ?: 0

        if (settings.noShowAction == "REQUEUE" && requeueCount < settings.maxRequeues) {
            // Requeue to global queue
            redis.execute(REQUEUE_TICKET_SCRIPT, listOf(
                RedisKeyManager.serving(storeId),
                RedisKeyManager.queue(storeId),
                RedisKeyManager.ticket(storeId, ticketId)
            ), listOf(
                ticketId.toString(),
                settings.requeueOffset.toString(),
                RedisTTLPolicy.TICKET_WAITING.toSeconds().toString(),
                System.currentTimeMillis().toString()
            )).next().awaitSingleOrNull()

            // Also requeue to service-type queue if applicable
            val serviceTypeIdStr = ticketData["service_type_id"]
            if (serviceTypeIdStr != null) {
                val stId = runCatching { UUID.fromString(serviceTypeIdStr) }.getOrNull()
                if (stId != null) {
                    val serviceTypeQueueKey = RedisKeyManager.queue(storeId, stId)
                    val queueSize = redis.opsForZSet().size(serviceTypeQueueKey).awaitSingle()
                    val insertIndex = minOf(settings.requeueOffset.toLong(), queueSize)
                    val scoreAtIndex = if (insertIndex > 0 && queueSize > 0) {
                        redis.opsForZSet().range(serviceTypeQueueKey, Range.closed(insertIndex - 1, insertIndex - 1))
                            .collectList().awaitSingle().firstOrNull()?.let { member ->
                                redis.opsForZSet().score(serviceTypeQueueKey, member).awaitSingleOrNull()?.let { it + 0.001 }
                            }
                    } else null
                    val score = scoreAtIndex ?: System.currentTimeMillis().toDouble()
                    redis.opsForZSet().add(serviceTypeQueueKey, ticketId.toString(), score).awaitSingleOrNull()
                }
            }

            redis.opsForHash<String, String>()
                .put(RedisKeyManager.ticket(storeId, ticketId), "requeue_count", (requeueCount + 1).toString())
                .awaitSingle()

            queueEventBroadcaster.broadcast(QueueSseEvent(
                type = "TICKET_REQUEUED", storeId = storeId, ticketId = ticketId,
                ticketNumber = ticketData["number"]
            ))
        } else {
            redisQueueRepository.removeFromServing(storeId, ticketId)
            redisTicketRepository.markSkipped(storeId, ticketId)
            fcmNotificationService?.removeToken(storeId, ticketId)

            queueEventBroadcaster.broadcast(QueueSseEvent(
                type = "TICKET_SKIPPED", storeId = storeId, ticketId = ticketId,
                ticketNumber = ticketData["number"]
            ))

            analyticsEventService?.recordTicketSkipped(
                storeId, ticketId, ticketData["number"] ?: "",
                parseStoredTimestamp(ticketData["issued_at"])
            )
        }

        redis.delete(RedisKeyManager.graceExpiry(storeId, ticketId)).awaitSingleOrNull()
    }

    suspend fun transferTicket(storeId: UUID, ticketId: UUID, targetServiceTypeId: UUID) {
        val ticketData = redisTicketRepository.getTicket(storeId, ticketId)
        if (ticketData.isEmpty()) throw NotFoundException("Ticket", "id", ticketId.toString())

        val status = TicketStatus.from(ticketData["status"])
        if (status != TicketStatus.WAITING) {
            throw ConflictException("Only WAITING tickets can be transferred")
        }

        val targetServiceType = serviceTypeRepository.findById(targetServiceTypeId)
            ?: throw NotFoundException("ServiceType", "id", targetServiceTypeId.toString())
        if (targetServiceType.storeId != storeId) {
            throw NotFoundException("ServiceType", "id", targetServiceTypeId.toString())
        }

        // Get score from global queue (original issue time)
        val score = redis.opsForZSet()
            .score(RedisKeyManager.queue(storeId), ticketId.toString())
            .awaitSingleOrNull() ?: Instant.now().toEpochMilli().toDouble()

        // Remove from current service-type queue
        val currentServiceTypeId = ticketData["service_type_id"]
        if (currentServiceTypeId != null) {
            val stId = runCatching { UUID.fromString(currentServiceTypeId) }.getOrNull()
            if (stId != null) {
                redis.opsForZSet().remove(RedisKeyManager.queue(storeId, stId), ticketId.toString()).awaitSingleOrNull()
            }
        }

        // Add to target service-type queue
        redis.opsForZSet()
            .add(RedisKeyManager.queue(storeId, targetServiceTypeId), ticketId.toString(), score)
            .awaitSingle()

        // Update service_type_id on ticket hash
        redis.opsForHash<String, String>()
            .put(RedisKeyManager.ticket(storeId, ticketId), "service_type_id", targetServiceTypeId.toString())
            .awaitSingle()

        log.info("Ticket transferred: store={} ticket={} target={}", storeId, ticketId, targetServiceType.name)

        queueEventBroadcaster.broadcast(QueueSseEvent(
            type = "TICKET_TRANSFERRED",
            storeId = storeId,
            ticketId = ticketId,
            ticketNumber = ticketData["number"]
        ))
    }

    private suspend fun setGraceExpiryIfNeeded(storeId: UUID, ticketId: UUID) {
        val settings = findApplicableNoShowSettings(storeId) ?: return
        if (settings.gracePeriodSec <= 0) return

        redis.opsForValue()
            .set(
                RedisKeyManager.graceExpiry(storeId, ticketId),
                "1",
                Duration.ofSeconds(settings.gracePeriodSec.toLong())
            )
            .awaitSingle()
    }

    private suspend fun sendProactiveAlerts(storeId: UUID) {
        if (fcmNotificationService == null) return
        val settings = storeSettingsRepository.findById(storeId)
        val threshold = settings?.alertThreshold ?: 2
        val storeName = storeRepository.findById(storeId)?.name ?: ""

        val waitingIds = redisQueueRepository.getWaitingTicketIds(storeId)
        for ((index, waitingTicketIdStr) in waitingIds.take(threshold).withIndex()) {
            val position = (index + 1).toLong()
            val waitingTicketId = runCatching { UUID.fromString(waitingTicketIdStr) }.getOrNull() ?: continue
            val ticketData = redisTicketRepository.getTicket(storeId, waitingTicketId)
            val ticketNumber = ticketData["number"]

            fcmNotificationService.sendProactiveAlert(
                storeId, waitingTicketId, ticketNumber, position, storeName
            )
        }
    }

    private suspend fun findApplicableNoShowSettings(storeId: UUID): StoreSettings? {
        val store = storeRepository.findById(storeId) ?: return null
        val settings = storeSettingsRepository.findById(storeId)
        return resolveApplicableNoShowSettings(store, settings)
    }

    private suspend fun requireNoShowSettings(storeId: UUID): StoreSettings {
        val store = storeRepository.findById(storeId)
            ?: throw NotFoundException("Store", "id", storeId.toString())

        if (!store.allowNoShow) {
            throw ConflictException("No-show handling is disabled for this store.")
        }

        return storeSettingsRepository.findById(storeId)
            ?: throw NotFoundException("StoreSettings", "storeId", storeId.toString())
    }

    private fun parseStoredTimestamp(rawValue: String?): Instant? {
        val numeric = rawValue?.toLongOrNull() ?: return null
        return if (numeric > 9_999_999_999L) Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
    }
}
