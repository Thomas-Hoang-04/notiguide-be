package com.thomas.notiguide.domain.admin.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.support.TestPrincipals
import com.thomas.notiguide.support.TestSecurityConfig
import io.mockk.coEvery
import io.mockk.coVerify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@WebFluxTest(
    controllers = [JoinRequestController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class JoinRequestControllerTest {
    @MockkBean lateinit var joinRequestService: JoinRequestService
    @MockkBean(relaxed = true) lateinit var storeRepository: StoreRepository

    @Autowired lateinit var client: WebTestClient

    private val requestId = "req-1"
    private val savedAdmin = Admin(username = "joiner", passwordHash = "hash")

    private fun orgPayload(orgId: UUID) = JoinRequestService.JoinRequestPayload(
        username = "joiner",
        passwordHash = "hash",
        targetType = JoinRequestService.TargetType.ORG,
        targetId = orgId.toString(),
        createdAt = "2026-06-13T00:00:00Z",
    )

    private fun storePayload(storeId: UUID) = JoinRequestService.JoinRequestPayload(
        username = "joiner",
        passwordHash = "hash",
        targetType = JoinRequestService.TargetType.STORE,
        targetId = storeId.toString(),
        createdAt = "2026-06-13T00:00:00Z",
    )

    @Test
    fun `org approval as super admin maps to AsSuperAdmin`() {
        val orgId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns orgPayload(orgId)
        coEvery { joinRequestService.approve(any(), any(), any()) } returns savedAdmin

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"role":"ROLE_SUPER_ADMIN"}""")
            .exchange()
            .expectStatus().isNoContent

        coVerify { joinRequestService.approve(requestId, JoinRequestService.Approval.AsSuperAdmin(orgId), any()) }
    }

    @Test
    fun `org approval as admin with a valid store maps to AsAdmin`() {
        val orgId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns orgPayload(orgId)
        coEvery { storeRepository.findById(storeId) } returns Store(id = storeId, orgId = orgId, name = "Main")
        coEvery { joinRequestService.approve(any(), any(), any()) } returns savedAdmin

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"role":"ROLE_ADMIN","storeId":"$storeId"}""")
            .exchange()
            .expectStatus().isNoContent

        coVerify { joinRequestService.approve(requestId, JoinRequestService.Approval.AsAdmin(storeId), any()) }
    }

    @Test
    fun `org approval as admin without a store is 409`() {
        val orgId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns orgPayload(orgId)

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"role":"ROLE_ADMIN"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `super admin role on a store target is forbidden`() {
        val storeId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns storePayload(storeId)

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN, storeId = storeId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"role":"ROLE_SUPER_ADMIN"}""")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `non super admin cannot approve an org request`() {
        val orgId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns orgPayload(orgId)

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN, storeId = UUID.randomUUID())))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"role":"ROLE_ADMIN","storeId":"${UUID.randomUUID()}"}""")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `verified store admin approves their own store request`() {
        val storeId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns storePayload(storeId)
        coEvery { joinRequestService.approve(any(), any(), any()) } returns savedAdmin

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN, storeId = storeId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus().isNoContent

        coVerify { joinRequestService.approve(requestId, JoinRequestService.Approval.AsAdmin(storeId), any()) }
    }

    @Test
    fun `body omitting role defaults to admin`() {
        val orgId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        coEvery { joinRequestService.get(requestId) } returns orgPayload(orgId)
        coEvery { storeRepository.findById(storeId) } returns Store(id = storeId, orgId = orgId, name = "Main")
        coEvery { joinRequestService.approve(any(), any(), any()) } returns savedAdmin

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .post().uri("/api/admins/requests/$requestId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"storeId":"$storeId"}""")
            .exchange()
            .expectStatus().isNoContent

        coVerify { joinRequestService.approve(requestId, JoinRequestService.Approval.AsAdmin(storeId), any()) }
    }
}
