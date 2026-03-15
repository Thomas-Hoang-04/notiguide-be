package com.thomas.notiguide.domain.queue.repository

import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Repository
class RedisCounterRepository(
    private val redis: ReactiveRedisTemplate<String, String>,
    appProperties: AppProperties
) {

    companion object {
        private val INCR_WITH_EXPIREAT_SCRIPT = RedisScript.of(
            """
            local val = redis.call('INCR', KEYS[1])
            if val == 1 then
                redis.call('EXPIREAT', KEYS[1], ARGV[1])
            end
            return val
            """.trimIndent(),
            Long::class.java
        )
    }

    private val storeTimezone: ZoneId = ZoneId.of(appProperties.timezone)

    suspend fun getNextNumber(storeId: UUID): Long {
        val today = LocalDate.now(storeTimezone)
        val key = RedisKeyManager.counter(storeId, today)
        val midnight = today
            .plusDays(1)
            .atStartOfDay(storeTimezone)
            .toInstant()

        return redis.execute(
            INCR_WITH_EXPIREAT_SCRIPT,
            listOf(key),
            listOf(midnight.epochSecond.toString())
        ).next().awaitSingle()
    }

    suspend fun getCurrentCount(storeId: UUID): Long =
        redis.opsForValue()
            .getAndAwait(RedisKeyManager.counter(storeId, LocalDate.now(storeTimezone)))
            ?.toLongOrNull() ?: 0L
}

