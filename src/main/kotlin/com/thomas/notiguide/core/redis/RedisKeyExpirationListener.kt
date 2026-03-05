package com.thomas.notiguide.core.redis

import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@Component
class RedisKeyExpirationListener(
    private val connectionFactory: ReactiveRedisConnectionFactory,
    private val queueRepo: RedisQueueRepository
) : DisposableBean {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var container: ReactiveRedisMessageListenerContainer? = null

    @EventListener(ApplicationReadyEvent::class)
    fun startListening() {
        try {
            val listenerContainer = ReactiveRedisMessageListenerContainer(connectionFactory)
            this.container = listenerContainer

            scope.launch {
                listenerContainer.receive(PatternTopic("__keyevent@0__:expired"))
                    .asFlow()
                    .collect { message ->
                        val expiredKey = message.message
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
            }
            log.info("Redis keyspace expiration listener started")
        } catch (e: Exception) {
            log.warn("Failed to start Redis keyspace listener — expiration cleanup will not run: {}", e.message)
        }
    }

    override fun destroy() {
        log.info("Shutting down Redis keyspace expiration listener")
        scope.cancel()
        try {
            container?.destroyLater()?.block()
        } catch (e: Exception) {
            log.warn("Failed to cleanly shut down Redis listener container: {}", e.message)
        }
    }
}
