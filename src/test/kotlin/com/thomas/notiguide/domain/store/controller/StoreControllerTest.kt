package com.thomas.notiguide.domain.store.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.service.InviteLinkService
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.organization.response.InviteLinkResponse
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.service.StoreService
import com.thomas.notiguide.shared.principal.StoreAccessService
import com.thomas.notiguide.support.TestPrincipals
import com.thomas.notiguide.support.TestSecurityConfig
import io.mockk.coEvery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@WebFluxTest(
    controllers = [StoreController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class StoreControllerTest {
    @MockkBean lateinit var storeService: StoreService
    @MockkBean lateinit var storeAccess: StoreAccessService
    @MockkBean lateinit var inviteLinkService: InviteLinkService

    @Autowired lateinit var client: WebTestClient

    private val storeId = UUID.randomUUID()

    private fun storeDto(orgId: UUID?) = StoreDto(
        id = storeId,
        publicId = "st_pub",
        orgId = orgId,
        name = "Store",
        address = null,
        isActive = true,
        allowJumpCall = false,
        allowNoShow = false,
        createdAt = null,
        updatedAt = null
    )

    @Test
    fun `GET invite-link returns 200 for an independent store`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } returns Unit
        coEvery { storeService.getStore(storeId) } returns storeDto(orgId = null)
        coEvery { inviteLinkService.getActive(JoinRequestService.TargetType.STORE, storeId) } returns
            InviteLinkResponse(token = "i_live", expiresAt = "2026-06-18T10:00:00Z")
        coEvery { inviteLinkService.getRecentUses(JoinRequestService.TargetType.STORE, storeId) } returns
            emptyList()

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/stores/$storeId/invite-link")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token").isEqualTo("i_live")
    }

    @Test
    fun `GET invite-link returns 409 for an org-owned store`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } returns Unit
        coEvery { storeService.getStore(storeId) } returns storeDto(orgId = UUID.randomUUID())

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/stores/$storeId/invite-link")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `POST rotate returns 409 for an org-owned store`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } returns Unit
        coEvery { storeService.getStore(storeId) } returns storeDto(orgId = UUID.randomUUID())

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .post().uri("/api/stores/$storeId/invite-link/rotate")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `POST rotate returns 403 when store access is denied`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } throws ForbiddenException("forbidden")

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .post().uri("/api/stores/$storeId/invite-link/rotate")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `POST rotate returns the freshly minted link for an independent store`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } returns Unit
        coEvery { storeService.getStore(storeId) } returns storeDto(orgId = null)
        coEvery { inviteLinkService.regenerate(JoinRequestService.TargetType.STORE, storeId) } returns
            InviteLinkResponse(token = "i_fresh", expiresAt = "2026-06-18T10:00:00Z")
        coEvery { inviteLinkService.getRecentUses(JoinRequestService.TargetType.STORE, storeId) } returns
            emptyList()

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .post().uri("/api/stores/$storeId/invite-link/rotate")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token").isEqualTo("i_fresh")
            .jsonPath("$.recentUses").isEmpty
    }
}
