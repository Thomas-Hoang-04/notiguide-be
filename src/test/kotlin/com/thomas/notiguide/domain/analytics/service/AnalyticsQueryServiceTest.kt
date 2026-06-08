package com.thomas.notiguide.domain.analytics.service

import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.domain.analytics.repository.AnalyticsEventRepository
import com.thomas.notiguide.domain.queue.repository.RedisCounterRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.util.UUID

class AnalyticsQueryServiceTest {
    private val analyticsEventRepository = mockk<AnalyticsEventRepository>(relaxed = true)
    private val redisQueueRepository = mockk<RedisQueueRepository>()
    private val redisCounterRepository = mockk<RedisCounterRepository>()
    private val storeRepository = mockk<StoreRepository>(relaxed = true)
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    // Use a real AppProperties: the service evaluates ZoneId.of(appProperties.timezone) at
    // construction time, so a relaxed mock (empty timezone string) would throw on construction.
    private val appProperties = AppProperties()
    private val service = AnalyticsQueryService(
        analyticsEventRepository, redisQueueRepository, redisCounterRepository,
        storeRepository, redis, appProperties,
    )

    @Test
    fun `getRealtimeStats maps queue, serving, and issued-today counts`() = runTest {
        val storeId = UUID.randomUUID()
        coEvery { redisQueueRepository.getQueueSize(storeId) } returns 5L
        coEvery { redisQueueRepository.getServingCount(storeId) } returns 2L
        coEvery { redisCounterRepository.getCurrentCount(storeId) } returns 10L
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty() // no avg-service-duration cached

        val stats = service.getRealtimeStats(storeId)

        assertThat(stats.currentQueueSize).isEqualTo(5)
        assertThat(stats.currentServingCount).isEqualTo(2)
        assertThat(stats.ticketsIssuedToday).isEqualTo(10)
    }
}
