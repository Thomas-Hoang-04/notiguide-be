package com.thomas.notiguide.domain.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.domain.organization.entity.Organization
import com.thomas.notiguide.domain.organization.repository.OrganizationRepository
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.ReactiveZSetOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID

class InviteLinkServiceTest {
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val valueOps = mockk<ReactiveValueOperations<String, String>>()
    private val zSetOps = mockk<ReactiveZSetOperations<String, String>>()
    private val organizationRepository = mockk<OrganizationRepository>()
    private val storeRepository = mockk<StoreRepository>()
    private val service =
        InviteLinkService(redis, jacksonObjectMapper(), organizationRepository, storeRepository)

    private val orgId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val storeId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    init {
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zSetOps
    }

    @Test
    fun `getActive returns null when no active entry exists`() = runTest {
        every { valueOps.get("invite:active:ORG:$orgId") } returns Mono.empty()
        assertThat(service.getActive(JoinRequestService.TargetType.ORG, orgId)).isNull()
    }

    @Test
    fun `getActive returns the active link state without minting`() = runTest {
        every { valueOps.get("invite:active:ORG:$orgId") } returns
            Mono.just("""{"token":"i_active123","expiresAt":"2026-06-18T10:00:00Z"}""")

        val link = service.getActive(JoinRequestService.TargetType.ORG, orgId)

        assertThat(link?.token).isEqualTo("i_active123")
        assertThat(link?.expiresAt).isEqualTo("2026-06-18T10:00:00Z")
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @Test
    fun `regenerate deletes the old token before writing the new pair with TTLs`() = runTest {
        every { valueOps.setIfAbsent("invite:lock:ORG:$orgId", any(), Duration.ofSeconds(10)) } returns
            Mono.just(true)
        every { valueOps.get("invite:active:ORG:$orgId") } returns
            Mono.just("""{"token":"i_old","expiresAt":"2026-06-12T00:00:00Z"}""")
        every { redis.delete("invite:token:i_old") } returns Mono.just(1L)
        every { valueOps.set(any(), any(), RedisTTLPolicy.INVITE_LINK) } returns Mono.just(true)
        every { redis.delete("invite:lock:ORG:$orgId") } returns Mono.just(1L)

        val link = service.regenerate(JoinRequestService.TargetType.ORG, orgId)

        assertThat(link.token).startsWith("i_").isNotEqualTo("i_old")
        assertThat(link.expiresAt).isNotNull()
        verifyOrder {
            redis.delete("invite:token:i_old")
            valueOps.set(match { it.startsWith("invite:token:i_") }, any(), RedisTTLPolicy.INVITE_LINK)
            valueOps.set("invite:active:ORG:$orgId", any(), RedisTTLPolicy.INVITE_LINK)
            redis.delete("invite:lock:ORG:$orgId")
        }
    }

    @Test
    fun `regenerate without an existing link skips the revoke delete`() = runTest {
        every { valueOps.setIfAbsent("invite:lock:STORE:$storeId", any(), any<Duration>()) } returns
            Mono.just(true)
        every { valueOps.get("invite:active:STORE:$storeId") } returns Mono.empty()
        every { valueOps.set(any(), any(), RedisTTLPolicy.INVITE_LINK) } returns Mono.just(true)
        every { redis.delete("invite:lock:STORE:$storeId") } returns Mono.just(1L)

        val link = service.regenerate(JoinRequestService.TargetType.STORE, storeId)

        assertThat(link.token).startsWith("i_")
        verify(exactly = 0) { redis.delete(match<String> { it.startsWith("invite:token:") }) }
    }

    @Test
    fun `regenerate is rejected with a conflict while the per-tenant lock is held`() = runTest {
        every { valueOps.setIfAbsent("invite:lock:ORG:$orgId", any(), any<Duration>()) } returns
            Mono.just(false)

        val ex = runCatching { service.regenerate(JoinRequestService.TargetType.ORG, orgId) }
            .exceptionOrNull()

        assertThat(ex).isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @Test
    fun `regenerate releases the lock even when a redis write fails`() = runTest {
        every { valueOps.setIfAbsent("invite:lock:ORG:$orgId", any(), any<Duration>()) } returns
            Mono.just(true)
        every { valueOps.get("invite:active:ORG:$orgId") } returns Mono.empty()
        every { valueOps.set(any(), any(), any<Duration>()) } returns
            Mono.error(RuntimeException("redis down"))
        every { redis.delete("invite:lock:ORG:$orgId") } returns Mono.just(1L)

        runCatching { service.regenerate(JoinRequestService.TargetType.ORG, orgId) }

        verify { redis.delete("invite:lock:ORG:$orgId") }
    }

    @Test
    fun `resolve returns the target and never consumes the token`() = runTest {
        every { valueOps.get("invite:token:i_tok") } returns
            Mono.just("""{"targetType":"STORE","targetId":"$storeId","expiresAt":"2026-06-18T10:00:00Z"}""")

        val first = service.resolve("i_tok")
        val second = service.resolve("i_tok")

        assertThat(first?.targetId).isEqualTo(storeId.toString())
        assertThat(second?.targetType).isEqualTo(JoinRequestService.TargetType.STORE)
        verify(exactly = 0) { redis.delete(any<String>()) }
    }

    @Test
    fun `resolve returns null for unknown and non-invite tokens`() = runTest {
        every { valueOps.get(any<String>()) } returns Mono.empty()

        assertThat(service.resolve("i_unknown")).isNull()
        assertThat(service.resolve("o_notAnInviteToken")).isNull()
        // the non-invite prefix short-circuits before touching Redis
        verify(exactly = 1) { valueOps.get(any<String>()) }
    }

    @Test
    fun `resolveForDisplay returns the org name for an org target`() = runTest {
        every { valueOps.get("invite:token:i_tok") } returns
            Mono.just("""{"targetType":"ORG","targetId":"$orgId","expiresAt":"2026-06-18T10:00:00Z"}""")
        coEvery { organizationRepository.findById(orgId) } returns
            Organization(id = orgId, name = "Acme Group")

        val res = service.resolveForDisplay("i_tok")

        assertThat(res?.targetType).isEqualTo("ORG")
        assertThat(res?.name).isEqualTo("Acme Group")
    }

    @Test
    fun `resolveForDisplay returns null when the target was deleted`() = runTest {
        every { valueOps.get("invite:token:i_tok") } returns
            Mono.just("""{"targetType":"ORG","targetId":"$orgId","expiresAt":"2026-06-18T10:00:00Z"}""")
        coEvery { organizationRepository.findById(orgId) } returns null

        assertThat(service.resolveForDisplay("i_tok")).isNull()
    }

    @Test
    fun `resolveForDisplay returns null when the store became org-owned`() = runTest {
        every { valueOps.get("invite:token:i_tok") } returns
            Mono.just("""{"targetType":"STORE","targetId":"$storeId","expiresAt":"2026-06-18T10:00:00Z"}""")
        coEvery { storeRepository.findById(storeId) } returns
            Store(id = storeId, orgId = orgId, name = "Acme Store")

        assertThat(service.resolveForDisplay("i_tok")).isNull()
    }

    @Test
    fun `resolveForDisplay returns the store name for an independent store target`() = runTest {
        every { valueOps.get("invite:token:i_tok") } returns
            Mono.just("""{"targetType":"STORE","targetId":"$storeId","expiresAt":"2026-06-18T10:00:00Z"}""")
        coEvery { storeRepository.findById(storeId) } returns
            Store(id = storeId, orgId = null, name = "Indie Store")

        val res = service.resolveForDisplay("i_tok")

        assertThat(res?.targetType).isEqualTo("STORE")
        assertThat(res?.name).isEqualTo("Indie Store")
    }

    @Test
    fun `recordUse appends then prunes then caps then refreshes the key TTL`() = runTest {
        val key = "invite:audit:STORE:$storeId"
        every { zSetOps.add(key, any(), any()) } returns Mono.just(true)
        every { zSetOps.removeRangeByScore(key, any()) } returns Mono.just(0L)
        every { zSetOps.removeRange(key, Range.closed(0L, -201L)) } returns Mono.just(0L)
        every { redis.expire(key, RedisTTLPolicy.INVITE_AUDIT) } returns Mono.just(true)

        service.recordUse(JoinRequestService.TargetType.STORE, storeId, "newjoiner", "i_abcd1234")

        verifyOrder {
            zSetOps.add(
                key,
                match { it.contains("\"username\":\"newjoiner\"") && it.contains("\"linkId\":\"1234\"") },
                any()
            )
            zSetOps.removeRangeByScore(key, any())
            zSetOps.removeRange(key, Range.closed(0L, -201L))
            redis.expire(key, RedisTTLPolicy.INVITE_AUDIT)
        }
    }

    @Test
    fun `recordUse never throws when redis fails`() = runTest {
        every { zSetOps.add(any(), any(), any()) } returns Mono.error(RuntimeException("redis down"))

        // must complete normally — a trail failure must never fail a registration
        service.recordUse(JoinRequestService.TargetType.ORG, orgId, "joiner", "i_abcd1234")
    }

    @Test
    fun `getRecentUses returns newest-first capped at 20 and skips malformed entries`() = runTest {
        val key = "invite:audit:ORG:$orgId"
        every { zSetOps.removeRangeByScore(key, any()) } returns Mono.just(0L)
        every { zSetOps.reverseRange(key, Range.closed(0L, 19L)) } returns Flux.just(
            """{"username":"newest","usedAt":"2026-06-11T10:00:00Z","linkId":"1234"}""",
            "not-json",
            """{"username":"older","usedAt":"2026-06-10T10:00:00Z","linkId":"5678"}"""
        )

        val uses = service.getRecentUses(JoinRequestService.TargetType.ORG, orgId)

        assertThat(uses).extracting("username").containsExactly("newest", "older")
        assertThat(uses).extracting("linkId").containsExactly("1234", "5678")
        verify { zSetOps.removeRangeByScore(key, any()) }
    }
}
