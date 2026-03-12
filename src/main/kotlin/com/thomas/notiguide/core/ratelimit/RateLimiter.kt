package com.thomas.notiguide.core.ratelimit

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class RateLimiter(
    private val redis: ReactiveRedisTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val rateLimiterScript: RedisScript<List<Any>> = DefaultRedisScript<List<Any>>().apply {
        setLocation(ClassPathResource("redis/rate_limiter.lua"))
        @Suppress("UNCHECKED_CAST")
        resultType = List::class.java as Class<List<Any>>
    }

    suspend fun isAllowed(
        key: String,
        windowSeconds: Long,
        maxRequests: Long
    ): RateLimitResult {
        if (windowSeconds <= 0 || maxRequests <= 0) {
            log.warn("Invalid rate limit configuration for key={} windowSeconds={} maxRequests={}", key, windowSeconds, maxRequests)
            return failOpenResult()
        }

        val nowMillis = Instant.now().toEpochMilli()
        val requestMember = UUID.randomUUID().toString()

        return try {
            val scriptResult = redis.execute(
                rateLimiterScript,
                listOf(key),
                listOf(
                    nowMillis.toString(),
                    windowSeconds.toString(),
                    maxRequests.toString(),
                    requestMember
                )
            ).next().awaitSingleOrNull()

            parseScriptResult(scriptResult, nowMillis, windowSeconds)
        } catch (ex: RedisConnectionFailureException) {
            log.warn(
                "Rate limiter fail-open: Redis connection unavailable [key={} windowSeconds={} maxRequests={}]",
                key,
                windowSeconds,
                maxRequests,
                ex
            )
            failOpenResult()
        } catch (ex: DataAccessException) {
            log.warn(
                "Rate limiter fail-open: Redis operation failed [key={} windowSeconds={} maxRequests={}]",
                key,
                windowSeconds,
                maxRequests,
                ex
            )
            failOpenResult()
        } catch (ex: Exception) {
            log.warn(
                "Rate limiter fail-open: unexpected limiter error [key={} windowSeconds={} maxRequests={}]",
                key,
                windowSeconds,
                maxRequests,
                ex
            )
            failOpenResult()
        }
    }

    private fun parseScriptResult(
        scriptResult: List<Any>?,
        nowMillis: Long,
        windowSeconds: Long
    ): RateLimitResult {
        if (scriptResult.isNullOrEmpty()) {
            log.warn("Rate limiter fail-open: empty script response")
            return failOpenResult()
        }

        val allowed = scriptResult.getOrNull(0).asLong() == 1L
        val remaining = (scriptResult.getOrNull(1).asLong() ?: 0L).coerceAtLeast(0)
        val defaultResetAtMillis = nowMillis + (windowSeconds * 1000)
        val resetAtMillis = scriptResult.getOrNull(2).asLong() ?: defaultResetAtMillis
        val resetAtEpochSeconds = if (resetAtMillis > 0) resetAtMillis / 1000 else 0

        return RateLimitResult(
            allowed = allowed,
            remaining = remaining,
            resetAtEpochSeconds = resetAtEpochSeconds
        )
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> this.toLong()
        is String -> this.toLongOrNull()
        else -> null
    }

    private fun failOpenResult(): RateLimitResult =
        RateLimitResult(
            allowed = true,
            remaining = -1,
            resetAtEpochSeconds = 0
        )
}

data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Long,
    val resetAtEpochSeconds: Long
)
