package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.incrementAndAwait
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RedisCounterRepository(
    private val redis: ReactiveStringRedisTemplate
) {

    suspend fun getNextNumber(storeId: UUID): Long {
        val key = RedisKeyManager.counter(storeId)
        val number = redis.opsForValue().incrementAndAwait(key)
        redis.expire(key, RedisTTLPolicy.DAILY_COUNTER).awaitSingle()
        return number
    }

    suspend fun getCurrentCount(storeId: UUID): Long =
        redis.opsForValue()
            .getAndAwait(RedisKeyManager.counter(storeId))
            ?.toLongOrNull() ?: 0L
}
