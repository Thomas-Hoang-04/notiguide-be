package com.thomas.notiguide.domain.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import reactor.core.publisher.Mono

class JoinRequestServiceTest {
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val adminRepository = mockk<AdminRepository>(relaxed = true)
    private val service = JoinRequestService(redis, jacksonObjectMapper(), adminRepository)

    // usernameReserved consults only the Redis username index (redis.hasKey), not the admin table.
    @Test
    fun `usernameReserved is true when the redis username index holds the key`() = runTest {
        every { redis.hasKey(any()) } returns Mono.just(true)
        assertThat(service.usernameReserved("taken")).isTrue()
        verify { adminRepository wasNot Called }
    }

    @Test
    fun `usernameReserved is false when the redis username index is empty`() = runTest {
        every { redis.hasKey(any()) } returns Mono.just(false)
        assertThat(service.usernameReserved("free")).isFalse()
        verify { adminRepository wasNot Called }
    }
}
