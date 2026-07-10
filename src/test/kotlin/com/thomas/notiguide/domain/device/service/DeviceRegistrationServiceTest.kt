package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import com.thomas.notiguide.core.device.DeviceTransmitterProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.device.entity.Device
import com.thomas.notiguide.domain.device.redis.DeviceActivationRecord
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.repository.DeviceRfCodeRepository
import com.thomas.notiguide.domain.device.types.DeviceActivationStatus
import com.thomas.notiguide.domain.device.types.DeviceFamily
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
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class DeviceRegistrationServiceTest {
    // findAndRegisterModules() pulls in JavaTimeModule so DeviceActivationRecord's OffsetDateTime round-trips.
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val enrollmentTokenService = mockk<EnrollmentTokenService>()
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)
    private val deviceRfCodeRepository = mockk<DeviceRfCodeRepository>(relaxed = true)
    private val publisher = mockk<DeviceMqttPublisher>(relaxed = true)
    private val txProps = mockk<DeviceTransmitterProperties>(relaxed = true)
    private val redis = mockk<ReactiveRedisTemplate<String, String>>(relaxed = true)
    private val service = DeviceRegistrationService(
        objectMapper, enrollmentTokenService, deviceRepository,
        deviceRfCodeRepository, publisher, txProps, redis
    )

    private fun nonce16(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 1 })

    private fun registerPayload(nonce: String): String =
        """{"schema_version":1,"hardware_model":"ESP32-C3","kind":"TRANSMITTER_HUB",
           "firmware_version":"1.0.0","public_key_b64":"$VALID_P256_B64",
           "enrollment_token":"tok","registration_nonce":"$nonce"}""".trimIndent()

    // SHA-256 hex of the DER carried by VALID_P256_B64 — matches the service's stored fingerprint.
    private fun payloadKeyFingerprint(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(VALID_P256_B64))
            .joinToString("") { "%02x".format(it) }

    private fun stubStoredActivation(nonce: String, fingerprint: String, issuedAt: OffsetDateTime) {
        val record = DeviceActivationRecord(
            deviceId = UUID.randomUUID(),
            publicKeyFingerprint = fingerprint,
            registrationNonce = nonce,
            nonce = null,
            issuedAt = issuedAt,
            expiresAt = issuedAt.plusMinutes(15),
            status = DeviceActivationStatus.PENDING
        )
        val valueOps = mockk<ReactiveValueOperations<String, String>>(relaxed = true)
        every { redis.opsForValue() } returns valueOps
        every { redis.hasKey(RedisKeyManager.deviceActivation(nonce)) } returns Mono.just(true)
        every { valueOps.get(RedisKeyManager.deviceActivation(nonce)) } returns
            Mono.just(objectMapper.writeValueAsString(record))
    }

    @Test
    fun `rejects nonce_in_use when a different device holds the nonce`() = runTest {
        val nonce = nonce16()
        stubStoredActivation(nonce, "a-different-device-fingerprint", OffsetDateTime.now(ZoneOffset.UTC))

        service.onRegister(registerPayload(nonce), DeviceFamily.TRANSMITTER)

        coVerify(exactly = 1) { publisher.publishRejected(DeviceFamily.TRANSMITTER, nonce, "nonce_in_use") }
        coVerify(exactly = 0) { enrollmentTokenService.consume(any()) }
        coVerify(exactly = 0) { publisher.publishPending(any(), any(), any()) }
    }

    @Test
    fun `same-device duplicate re-publishes pending without consuming the token`() = runTest {
        val nonce = nonce16()
        val issuedAt = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC)
        stubStoredActivation(nonce, payloadKeyFingerprint(), issuedAt)

        service.onRegister(registerPayload(nonce), DeviceFamily.TRANSMITTER)

        coVerify(exactly = 1) { publisher.publishPending(DeviceFamily.TRANSMITTER, nonce, issuedAt) }
        coVerify(exactly = 0) { publisher.publishRejected(DeviceFamily.TRANSMITTER, nonce, "nonce_in_use") }
        coVerify(exactly = 0) { enrollmentTokenService.consume(any()) }
        coVerify(exactly = 0) { deviceRepository.save(any()) }
    }

    @Test
    fun `rejects registration when nonce decodes to fewer than 16 bytes`() = runTest {
        val shortNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8) { 1 })

        service.onRegister(registerPayload(shortNonce), DeviceFamily.TRANSMITTER)

        coVerify(exactly = 0) { enrollmentTokenService.consume(any()) }
        coVerify(exactly = 0) { publisher.publishPending(any(), any(), any()) }
        coVerify(exactly = 0) { publisher.publishRejected(any<DeviceFamily>(), any(), any()) }
    }

    @Test
    fun `writes activation keys under both nonce and device-id keys and publishes pending on fresh registration`() = runTest {
        val nonce = nonce16()
        val activationKey = RedisKeyManager.deviceActivation(nonce)
        val deviceId = UUID.randomUUID()
        val byDeviceKey = RedisKeyManager.deviceActivationByDevice(deviceId)
        val valueOps = mockk<ReactiveValueOperations<String, String>>(relaxed = true)

        every { redis.opsForValue() } returns valueOps
        every { redis.hasKey(activationKey) } returns Mono.just(false)
        every { valueOps.set(activationKey, any(), Duration.ofMinutes(15)) } returns Mono.just(true)
        every { valueOps.set(byDeviceKey, any(), Duration.ofMinutes(15)) } returns Mono.just(true)
        coEvery { enrollmentTokenService.consume("tok") } returns
            EnrollmentTokenService.EnrollmentTokenRecord(storeId = null)
        coEvery { deviceRepository.findByPublicKeyDer(any()) } returns null
        coEvery { deviceRepository.save(any()) } returns Device(
            id = deviceId,
            kind = DeviceKind.TRANSMITTER_HUB,
            status = DeviceStatus.PENDING
        )

        service.onRegister(registerPayload(nonce), DeviceFamily.TRANSMITTER)

        coVerify(exactly = 1) { valueOps.set(activationKey, any(), Duration.ofMinutes(15)) }
        coVerify(exactly = 1) { valueOps.set(byDeviceKey, any(), Duration.ofMinutes(15)) }
        coVerify(exactly = 1) { publisher.publishPending(DeviceFamily.TRANSMITTER, nonce, any()) }
    }

    companion object {
        // A real X.509 DER SubjectPublicKeyInfo for a P-256 key, base64.
        const val VALID_P256_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1myGosfAGn9EsTYuvkK+/5mYVeBHgYOjdyglV+aT+k3p8S8tudPF4QBLHjCxFPap9gWt9V7DGurs/CARIotsSw=="
    }
}
