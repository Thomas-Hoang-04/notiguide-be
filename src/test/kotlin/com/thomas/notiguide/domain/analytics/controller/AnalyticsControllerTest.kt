package com.thomas.notiguide.domain.analytics.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.analytics.response.OverviewRealtimeResponse
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

@WebFluxTest(
    controllers = [AnalyticsController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class AnalyticsControllerTest {
    @MockkBean lateinit var analyticsQueryService: com.thomas.notiguide.domain.analytics.service.AnalyticsQueryService
    @MockkBean(relaxed = true) lateinit var storeAccess: com.thomas.notiguide.shared.principal.StoreAccessService

    @Autowired lateinit var client: WebTestClient

    @Test
    fun `overview realtime is forbidden for a non-super admin`() {
        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN)))
            .get().uri("/api/analytics/overview/realtime")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `overview realtime is allowed for a super admin`() {
        coEvery { analyticsQueryService.getOverviewRealtime(any()) } returns
            OverviewRealtimeResponse(0, 0, 0, 0, null)

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_SUPER_ADMIN)))
            .get().uri("/api/analytics/overview/realtime")
            .exchange()
            .expectStatus().isOk
    }
}
