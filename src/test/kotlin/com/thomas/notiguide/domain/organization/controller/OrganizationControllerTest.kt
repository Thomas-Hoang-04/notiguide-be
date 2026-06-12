package com.thomas.notiguide.domain.organization.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.service.InviteLinkService
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.organization.response.InviteLinkResponse
import com.thomas.notiguide.domain.organization.dto.InviteLinkUse
import com.thomas.notiguide.domain.organization.service.OrganizationService
import com.thomas.notiguide.domain.store.repository.StoreRepository
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
    controllers = [OrganizationController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class OrganizationControllerTest {
    @MockkBean(relaxed = true) lateinit var organizationService: OrganizationService
    @MockkBean(relaxed = true) lateinit var storeRepository: StoreRepository
    @MockkBean lateinit var inviteLinkService: InviteLinkService

    @Autowired lateinit var client: WebTestClient

    private val orgId = UUID.randomUUID()

    @Test
    fun `GET invite-link returns the no-active-link state carrying the usage trail`() {
        coEvery { inviteLinkService.getActive(JoinRequestService.TargetType.ORG, orgId) } returns null
        coEvery { inviteLinkService.getRecentUses(JoinRequestService.TargetType.ORG, orgId) } returns listOf(
            InviteLinkUse(username = "joiner", usedAt = "2026-06-10T09:00:00Z", linkId = "abcd")
        )

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .get().uri("/api/orgs/me/invite-link")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token").isEmpty
            .jsonPath("$.expiresAt").isEmpty
            .jsonPath("$.recentUses[0].username").isEqualTo("joiner")
            .jsonPath("$.recentUses[0].linkId").isEqualTo("abcd")
    }

    @Test
    fun `GET invite-link is forbidden for a role-ADMIN principal`() {
        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN, storeId = UUID.randomUUID())))
            .get().uri("/api/orgs/me/invite-link")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `POST rotate returns the freshly minted link with the unchanged trail`() {
        coEvery { inviteLinkService.regenerate(JoinRequestService.TargetType.ORG, orgId) } returns
            InviteLinkResponse(token = "i_fresh", expiresAt = "2026-06-18T10:00:00Z")
        coEvery { inviteLinkService.getRecentUses(JoinRequestService.TargetType.ORG, orgId) } returns emptyList()

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN, orgId = orgId)))
            .post().uri("/api/orgs/me/invite-link/rotate")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token").isEqualTo("i_fresh")
            .jsonPath("$.expiresAt").isEqualTo("2026-06-18T10:00:00Z")
            .jsonPath("$.recentUses").isEmpty
    }

    @Test
    fun `POST rotate is forbidden for a role-ADMIN principal`() {
        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN, storeId = UUID.randomUUID())))
            .post().uri("/api/orgs/me/invite-link/rotate")
            .exchange()
            .expectStatus().isForbidden
    }
}
