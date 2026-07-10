package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.device.DeviceCanonical
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DevicePublicIdMinter
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class DeviceActivationServiceTest {
    // findAndRegisterModules() pulls in JavaTimeModule so DeviceActivationRecord's OffsetDateTime round-trips.
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val redis = mockk<ReactiveRedisTemplate<String, String>>(relaxed = true)
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)
    private val publisher = mockk<DeviceMqttPublisher>(relaxed = true)
    private val minter = mockk<DevicePublicIdMinter>()
    private val rfCodeService = mockk<RfCodeService>(relaxed = true)
    private val service = DeviceActivationService(objectMapper, redis, deviceRepository, publisher, minter, rfCodeService)

    private fun arrange(): Triple<KeyPair, String, DeviceActivationRecord> {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val nonce = "reg_nonce_16b_urlsafe"
        val deviceId = UUID.randomUUID()
        val issued = OffsetDateTime.now(ZoneOffset.UTC)
        val record = DeviceActivationRecord(
            deviceId = deviceId, publicKeyFingerprint = "fp", registrationNonce = nonce,
            nonce = "server16", issuedAt = issued, expiresAt = issued.plusMinutes(5),
            status = DeviceActivationStatus.ISSUED
        )
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns Mono.empty()
        every { valueOps.get(RedisKeyManager.deviceActivation(nonce)) } returns
            Mono.just(objectMapper.writeValueAsString(record))
        every { redis.delete(any<String>()) } returns Mono.just(1L)
        val device = Device(
            id = deviceId, publicKeyDer = kp.public.encoded, kind = DeviceKind.TRANSMITTER_HUB,
            status = DeviceStatus.PENDING, assignedName = "Hub A", storeId = UUID.randomUUID()
        )
        coEvery { deviceRepository.findById(deviceId) } returns device
        coEvery { deviceRepository.save(any()) } answers { firstArg() }
        coEvery { minter.mint(DeviceKind.TRANSMITTER_HUB) } returns "HUB-123" // mint is a suspend fun
        return Triple(kp, nonce, record)
    }

    private fun responsePayload(kp: KeyPair, canonical: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
            .apply { initSign(kp.private); update(canonical.toByteArray(Charsets.UTF_8)) }.sign()
        return """{"schema_version":1,"type":"response","signature_b64":"${Base64.getEncoder().encodeToString(sig)}"}"""
    }

    @Test
    fun `valid response activates the hub`() = runTest {
        val (kp, nonce, record) = arrange()
        val canonical = DeviceCanonical.activate(nonce, record.nonce!!, record.issuedAt, record.expiresAt)
        service.onResponse(responsePayload(kp, canonical), nonce)
        coVerify { deviceRepository.save(match { it.status == DeviceStatus.ACTIVE && it.publicId == "HUB-123" }) }
        coVerify { publisher.publishResult(DeviceKind.TRANSMITTER_HUB, nonce, "HUB-123", "Hub A", any()) }
    }

    @Test
    fun `tampered signature does not activate`() = runTest {
        val (kp, nonce, record) = arrange()
        val canonical = DeviceCanonical.activate(nonce, record.nonce!!, record.issuedAt, record.expiresAt)
        service.onResponse(responsePayload(kp, canonical + "TAMPER"), nonce)
        coVerify(exactly = 0) { deviceRepository.save(match { it.status == DeviceStatus.ACTIVE }) }
    }
}
