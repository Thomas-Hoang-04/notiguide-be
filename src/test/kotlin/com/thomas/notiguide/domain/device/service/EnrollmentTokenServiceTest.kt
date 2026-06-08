package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono

class EnrollmentTokenServiceTest {
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val storeRepository = mockk<com.thomas.notiguide.domain.store.repository.StoreRepository>(relaxed = true)
    private val storeAccess = mockk<com.thomas.notiguide.shared.principal.StoreAccessService>(relaxed = true)
    private val properties = mockk<com.thomas.notiguide.core.device.DeviceCommandSigningProperties>(relaxed = true)
    private val service = EnrollmentTokenService(redis, storeRepository, storeAccess, jacksonObjectMapper(), properties)

    @Test
    fun `consume returns null for an unknown token`() = runTest {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        // consume() uses getAndDelete (one-shot read), not get.
        every { valueOps.getAndDelete(any()) } returns Mono.empty()

        assertThat(service.consume("nonexistent-token")).isNull()
    }
}
