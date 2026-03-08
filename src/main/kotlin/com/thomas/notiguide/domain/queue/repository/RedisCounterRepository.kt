package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.incrementAndAwait
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Repository
class RedisCounterRepository(
    private val redis: ReactiveStringRedisTemplate
) {

    private val storeTimezone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    suspend fun getNextNumber(storeId: UUID): Long {
        val key = RedisKeyManager.counter(storeId)
        val number = redis.opsForValue().incrementAndAwait(key)
        val midnight = LocalDate.now(storeTimezone)
            .plusDays(1)
            .atStartOfDay(storeTimezone)
            .toInstant()
        redis.expireAt(key, midnight).awaitSingle()
        return number
    }

    suspend fun getCurrentCount(storeId: UUID): Long =
        redis.opsForValue()
            .getAndAwait(RedisKeyManager.counter(storeId))
            ?.toLongOrNull() ?: 0L
}

