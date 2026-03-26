package com.thomas.notiguide.core.redis

import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.service.QueueService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

@Component
class RedisKeyExpirationListener(
    private val connectionFactory: ReactiveRedisConnectionFactory,
    private val queueRepo: RedisQueueRepository,
    private val queueService: QueueService
) : DisposableBean {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var container: ReactiveRedisMessageListenerContainer? = null
    private val active = AtomicBoolean(false)

    companion object {
        private const val MAX_RETRIES = 5
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    }

    @Suppress("unused")
    val isListening: Boolean get() = active.get()

    @EventListener(ApplicationReadyEvent::class)
    fun startListening() {
        scope.launch {
            var attempt = 0
            while (attempt < MAX_RETRIES) {
                try {
                    subscribe()
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    val delayMs = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_DELAYS_MS.last() }
                    if (attempt < MAX_RETRIES) {
                        log.warn(
                            "Failed to start Redis keyspace listener (attempt {}/{}), retrying in {}ms: {}",
                            attempt, MAX_RETRIES, delayMs, e.message
                        )
                        delay(delayMs.milliseconds)
                    } else {
                        log.error(
                            "Failed to start Redis keyspace listener after {} attempts — " +
                                "expiration cleanup will not run. " +
                                "ServingSetCleanupScheduler will partially compensate for serving-set staleness.",
                            MAX_RETRIES, e
                        )
                    }
                }
            }
        }
    }

    private suspend fun subscribe() {
        val listenerContainer = ReactiveRedisMessageListenerContainer(connectionFactory)
        this.container = listenerContainer

        active.set(true)
        log.info("Redis keyspace expiration listener started")

        try {
            listenerContainer.receive(PatternTopic("__keyevent@0__:expired"))
                .asFlow()
                .collect { message ->
                    val expiredKey = message.message

                    if (RedisKeyManager.isGraceExpiryKey(expiredKey)) {
                        val (storeId, ticketId) = RedisKeyManager.parseGraceExpiryKey(expiredKey) ?: return@collect
                        log.info("Grace period expired: store={} ticket={}", storeId, ticketId)
                        try {
                            queueService.handleNoShow(storeId, ticketId)
                        } catch (e: Exception) {
                            log.error("Failed to handle no-show: store={} ticket={}", storeId, ticketId, e)
                        }
                        return@collect
                    }

                    if (!RedisKeyManager.isTicketKey(expiredKey)) return@collect

                    val (storeId, ticketId) = RedisKeyManager.parseTicketKey(expiredKey) ?: return@collect

                    log.info("Ticket expired: store={} ticket={}", storeId, ticketId)

                    try {
                        queueRepo.removeFromQueue(storeId, ticketId)
                        queueRepo.removeFromServing(storeId, ticketId)
                    } catch (e: Exception) {
                        log.error("Failed to cleanup expired ticket: store={} ticket={}", storeId, ticketId, e)
                    }
                }
        } catch (e: Exception) {
            active.set(false)
            container = null
            throw e
        }
    }

    override fun destroy() {
        log.info("Shutting down Redis keyspace expiration listener")
        active.set(false)
        scope.cancel()
        try {
            container?.destroyLater()?.block()
        } catch (e: Exception) {
            log.warn("Failed to cleanly shut down Redis listener container: {}", e.message)
        }
    }
}
