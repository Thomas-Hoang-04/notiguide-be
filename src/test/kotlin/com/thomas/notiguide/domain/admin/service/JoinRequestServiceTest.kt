package com.thomas.notiguide.domain.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.types.AdminRole
import io.mockk.CapturingSlot
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.ReactiveZSetOperations
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class JoinRequestServiceTest {
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val valueOps = mockk<ReactiveValueOperations<String, String>>()
    private val zSetOps = mockk<ReactiveZSetOperations<String, String>>()
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

    // --- approve ---

    // Stubs the full approve() Redis flow for an ORG-target request and returns the slot
    // that captures the Admin handed to adminRepository.save.
    private fun stubApproveForOrgRequest(requestId: String, username: String, orgId: UUID): CapturingSlot<Admin> {
        val payloadJson = jacksonObjectMapper().writeValueAsString(
            JoinRequestService.JoinRequestPayload(
                username = username,
                passwordHash = "hash",
                targetType = JoinRequestService.TargetType.ORG,
                targetId = orgId.toString(),
                createdAt = OffsetDateTime.now().toString(),
            ),
        )
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zSetOps
        every { valueOps.setIfAbsent(RedisKeyManager.joinRequestLock(requestId), any(), Duration.ofSeconds(30)) } returns Mono.just(true)
        every { valueOps.get(RedisKeyManager.joinRequest(requestId)) } returns Mono.just(payloadJson)
        every { redis.delete(RedisKeyManager.joinRequest(requestId)) } returns Mono.just(1L)
        every { redis.delete(RedisKeyManager.joinRequestUsername(username.lowercase())) } returns Mono.just(1L)
        every { redis.delete(RedisKeyManager.joinRequestLock(requestId)) } returns Mono.just(1L)
        every { zSetOps.remove(RedisKeyManager.joinRequestOrgIndex(orgId), requestId) } returns Mono.just(1L)
        coEvery { adminRepository.existsByUsername(any()) } returns false
        val saved = slot<Admin>()
        coEvery { adminRepository.save(capture(saved)) } answers { saved.captured }
        return saved
    }

    @Test
    fun `approve as super admin creates an org-wide owner with no store`() = runTest {
        val requestId = "req-super"
        val orgId = UUID.randomUUID()
        val verifierId = UUID.randomUUID()
        val saved = stubApproveForOrgRequest(requestId, "newowner", orgId)

        service.approve(requestId, JoinRequestService.Approval.AsSuperAdmin(orgId), verifierId)

        assertThat(saved.captured.role).isEqualTo(AdminRole.ROLE_SUPER_ADMIN)
        assertThat(saved.captured.orgId).isEqualTo(orgId)
        assertThat(saved.captured.storeId).isNull()
        assertThat(saved.captured.isVerified).isTrue()
        assertThat(saved.captured.verifiedBy).isEqualTo(verifierId)
    }

    @Test
    fun `approve as admin assigns the store and leaves org null`() = runTest {
        val requestId = "req-admin"
        val orgId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        val verifierId = UUID.randomUUID()
        val saved = stubApproveForOrgRequest(requestId, "newstaff", orgId)

        service.approve(requestId, JoinRequestService.Approval.AsAdmin(storeId), verifierId)

        assertThat(saved.captured.role).isEqualTo(AdminRole.ROLE_ADMIN)
        assertThat(saved.captured.storeId).isEqualTo(storeId)
        assertThat(saved.captured.orgId).isNull()
        assertThat(saved.captured.isVerified).isTrue()
        assertThat(saved.captured.verifiedBy).isEqualTo(verifierId)
    }

    @Test
    fun `approve throws when the join request lock is already held`() = runTest {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns Mono.just(false)
        val ex = runCatching {
            service.approve("req-locked", JoinRequestService.Approval.AsAdmin(UUID.randomUUID()), UUID.randomUUID())
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(ConflictException::class.java)
    }
}
