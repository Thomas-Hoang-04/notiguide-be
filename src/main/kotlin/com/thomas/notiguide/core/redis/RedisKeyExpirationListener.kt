package com.thomas.notiguide.core.redis

import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.stereotype.Component

@Component
class RedisKeyExpirationListener(
    private val connectionFactory: ReactiveRedisConnectionFactory,
    private val queueRepo: RedisQueueRepository
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @PostConstruct
    fun startListening() {
        val container = ReactiveRedisMessageListenerContainer(connectionFactory)
        scope.launch {
            container.receive(PatternTopic("__keyevent@0__:expired"))
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
    }
}
