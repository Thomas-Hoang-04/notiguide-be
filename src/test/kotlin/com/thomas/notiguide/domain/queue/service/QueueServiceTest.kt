package com.thomas.notiguide.domain.queue.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.queue.repository.RedisCounterRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.queue.repository.RedisTicketRepository
import com.thomas.notiguide.domain.queue.types.QueueState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.util.UUID

class QueueServiceTest {
    private val storeRepository = mockk<com.thomas.notiguide.domain.store.repository.StoreRepository>(relaxed = true)
    private val redisQueueRepository = mockk<RedisQueueRepository>()
    private val redisTicketRepository = mockk<RedisTicketRepository>()
    private val redisCounterRepository = mockk<RedisCounterRepository>(relaxed = true)
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val queueEventBroadcaster = mockk<com.thomas.notiguide.core.sse.QueueEventBroadcaster>(relaxed = true)
    private val deviceDispatchEventBroadcaster =
        mockk<com.thomas.notiguide.domain.device.service.DeviceDispatchEventBroadcaster>(relaxed = true)
    private val deviceQueryService = mockk<com.thomas.notiguide.domain.device.service.DeviceQueryService>(relaxed = true)
    private val storeSettingsRepository = mockk<com.thomas.notiguide.domain.store.repository.StoreSettingsRepository>(relaxed = true)
    private val serviceTypeRepository = mockk<com.thomas.notiguide.domain.store.repository.ServiceTypeRepository>(relaxed = true)

    private val service = QueueService(
        storeRepository, redisQueueRepository, redisTicketRepository, redisCounterRepository,
        redis, jacksonObjectMapper(), null, null, null, null,
        queueEventBroadcaster, deviceDispatchEventBroadcaster, deviceQueryService,
        storeSettingsRepository, serviceTypeRepository,
    )

    private val storeId = UUID.randomUUID()

    @Test
    fun `cleanupServingSet removes orphaned tickets and counts them`() = runTest {
        val ghost = UUID.randomUUID()
        every { redisQueueRepository.getServingTickets(storeId) } returns flowOf(ghost.toString())
        coEvery { redisTicketRepository.exists(storeId, ghost) } returns false
        coEvery { redisQueueRepository.removeFromServing(storeId, ghost) } returns 1L

        val cleaned = service.cleanupServingSet(storeId)

        assertThat(cleaned).isEqualTo(1)
        coVerify { redisQueueRepository.removeFromServing(storeId, ghost) }
    }

    @Test
    fun `getQueueState returns PAUSED when redis holds PAUSED`() = runTest {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(RedisKeyManager.queueState(storeId)) } returns Mono.just("PAUSED")

        assertThat(service.getQueueState(storeId)).isEqualTo(QueueState.PAUSED)
    }

    @Test
    fun `getQueueState defaults to ACTIVE when nothing is stored`() = runTest {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()

        assertThat(service.getQueueState(storeId)).isEqualTo(QueueState.ACTIVE)
    }
}
