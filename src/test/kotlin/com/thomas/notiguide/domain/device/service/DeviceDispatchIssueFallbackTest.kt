package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.domain.device.controller.DeviceConflictEnvelopeException
import com.thomas.notiguide.domain.queue.service.QueueService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.ReactiveRedisTemplate
import java.util.UUID

class DeviceDispatchIssueFallbackTest {
    private val deviceQueryService = mockk<DeviceQueryService>(relaxed = true)
    private val queueService = mockk<QueueService>(relaxed = true)
    private val redis = mockk<ReactiveRedisTemplate<String, String>>(relaxed = true)
    private val electionProvider = mockk<ObjectProvider<TransmitterElectionService>>()
    private val propsProvider = mockk<ObjectProvider<DeviceTransmitterProperties>>(relaxed = true)
    private val service = DeviceDispatchService(
        deviceQueryService, queueService, redis, jacksonObjectMapper().findAndRegisterModules(),
        electionProvider, propsProvider
    )
    private val storeId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()

    @Test
    fun `issuance without fallback throws when no active transmitter`() = runTest {
        every { electionProvider.ifAvailable } returns mockk {
            coEvery { electActive(storeId) } returns null
        }
        assertThatThrownBy {
            runBlocking {
                service.issueDeviceTicket(storeId, deviceId, null, allowSerialFallback = false)
            }
        }.isInstanceOf(DeviceConflictEnvelopeException::class.java)
    }

    @Test
    fun `issuance with fallback proceeds when no active transmitter`() = runTest {
        every { electionProvider.ifAvailable } returns mockk {
            coEvery { electActive(storeId) } returns null
        }
        // With allowSerialFallback=true, the no_active_transmitter guard is bypassed.
        // loadDispatchableDevice will throw device_not_dispatchable (relaxed deviceQueryService returns null).
        assertThatThrownBy {
            runBlocking {
                service.issueDeviceTicket(storeId, deviceId, null, allowSerialFallback = true)
            }
        }.isInstanceOf(DeviceConflictEnvelopeException::class.java)
            .hasMessageContaining("device_not_dispatchable")
    }
}
