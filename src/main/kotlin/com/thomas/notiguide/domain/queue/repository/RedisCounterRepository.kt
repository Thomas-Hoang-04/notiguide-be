package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RedisCounterRepository(
    private val redis: ReactiveRedisTemplate<String, Any>
) {

    suspend fun getNextNumber(storeId: UUID): Long {
        val key = RedisKeyManager.counter(storeId)
        val number = redis.opsForValue().increment(key).awaitSingle()
        redis.expire(key, RedisTTLPolicy.DAILY_COUNTER).awaitSingle()
        return number
    }

    suspend fun getCurrentCount(storeId: UUID): Long =
        redis.opsForValue()
            .get(RedisKeyManager.counter(storeId))
            .awaitSingleOrNull()
            ?.let { (it as? Number)?.toLong() } ?: 0L
}
