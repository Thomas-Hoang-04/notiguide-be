package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationByDeviceRecord
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.request.ApproveDeviceRequest
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DeviceApprovalServiceTest {
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)
    private val storeRepository = mockk<StoreRepository>(relaxed = true)
    private val deviceQueryService = mockk<DeviceQueryService>(relaxed = true)
    private val redis = mockk<ReactiveRedisTemplate<String, String>>(relaxed = true)
    private val publisher = mockk<DeviceMqttPublisher>(relaxed = true)
    private val publisherProvider = mockk<ObjectProvider<DeviceMqttPublisher>> { every { ifAvailable } returns publisher }
    private val txProps = mockk<DeviceTransmitterProperties> { every { maxRegisteredPerStore } returns 10 }
    private val storeAccess = mockk<StoreAccessService>(relaxed = true)
    // findAndRegisterModules() pulls in JavaTimeModule so DeviceActivationRecord's OffsetDateTime round-trips.
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val service = DeviceApprovalService(
        deviceRepository, storeRepository, deviceQueryService, objectMapper,
        redis, publisherProvider, txProps, storeAccess
    )

    @Test
    fun `approve issues a challenge on the record's registration nonce`() = runTest {
        val deviceId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        val nonce = "N16_urlsafe_nonce_x"
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(RedisKeyManager.deviceActivationByDevice(deviceId)) } returns
            Mono.just(objectMapper.writeValueAsString(DeviceActivationByDeviceRecord(registrationNonce = nonce)))
        every { valueOps.get(RedisKeyManager.deviceActivation(nonce)) } returns
            Mono.just(objectMapper.writeValueAsString(DeviceActivationRecord(
                deviceId = deviceId, registrationNonce = nonce, status = DeviceActivationStatus.PENDING,
                issuedAt = now, expiresAt = now.plusMinutes(15))))
        every { valueOps.set(any(), any(), any<Duration>()) } returns Mono.just(true)
        coEvery { deviceRepository.findById(deviceId) } returns Device(
            id = deviceId, publicKeyDer = ByteArray(1), kind = DeviceKind.TRANSMITTER_HUB,
            status = DeviceStatus.PENDING, storeId = storeId)
        coEvery { deviceRepository.save(any()) } answers { firstArg() }
        coEvery { deviceRepository.countRegisteredHubsByStoreExcludingPublicKey(any(), any()) } returns 0L
        coEvery { storeRepository.findById(storeId) } returns mockk(relaxed = true)
        coEvery { deviceQueryService.getRequiredDeviceDetailById(any()) } returns mockk(relaxed = true)

        service.approve(deviceId, ApproveDeviceRequest(assignedName = "Hub A", storeId = storeId), mockk<AdminPrincipal>(relaxed = true))

        coVerify { publisher.publishChallenge(DeviceKind.TRANSMITTER_HUB, nonce, any(), any(), any()) }
    }
}
