package com.thomas.notiguide.domain.store.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.store.response.StoreSlugListResponse
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
    controllers = [StoreSlugController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class StoreSlugControllerTest {
    @MockkBean lateinit var storeSlugService: com.thomas.notiguide.domain.store.service.StoreSlugService
    @MockkBean lateinit var storeAccess: com.thomas.notiguide.shared.principal.StoreAccessService

    @Autowired lateinit var client: WebTestClient

    private val storeId = UUID.randomUUID()

    @Test
    fun `GET slugs returns 200 when store access is granted`() {
        coEvery { storeAccess.requireStoreAccess(any(), any()) } returns Unit
        coEvery { storeSlugService.listSlugs(storeId) } returns
            StoreSlugListResponse(items = emptyList(), activeCount = 0, activeMax = 5, graceCount = 0, graceMax = 5)

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/stores/$storeId/slugs")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `GET slugs returns 403 when store access is denied`() {
        coEvery { storeAccess.requireStoreAccess(any(), any()) } throws
            com.thomas.notiguide.core.exception.ForbiddenException("forbidden")

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/stores/$storeId/slugs")
            .exchange()
            .expectStatus().isForbidden
    }
}
