package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Mono
import java.util.UUID

class HubDiagnosticsServiceTest {
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val properties = mockk<com.thomas.notiguide.core.device.DeviceTransmitterProperties>(relaxed = true)
    private val client = mockk<DatabaseClient>(relaxed = true)
    private val service = HubDiagnosticsService(redis, jacksonObjectMapper(), properties, client)

    @Test
    fun `loadDiagnostics returns null when nothing is cached`() = runTest {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()

        assertThat(service.loadDiagnostics(UUID.randomUUID())).isNull()
    }
}
