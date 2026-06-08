package com.thomas.notiguide.domain.queue.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
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
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@WebFluxTest(
    controllers = [QueueAdminController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class QueueAdminControllerTest {
    @MockkBean lateinit var queueService: com.thomas.notiguide.domain.queue.service.QueueService
    @MockkBean(relaxed = true) lateinit var broadcaster: com.thomas.notiguide.core.sse.QueueEventBroadcaster
    @MockkBean(relaxed = true) lateinit var dispatchService: com.thomas.notiguide.domain.device.service.DeviceDispatchService
    @MockkBean lateinit var storeAccess: com.thomas.notiguide.shared.principal.StoreAccessService

    @Autowired lateinit var client: WebTestClient

    private val storeId = UUID.randomUUID()

    @Test
    fun `GET size returns 200 when store access is granted`() {
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } returns Unit
        coEvery { queueService.getQueueSize(storeId) } returns 7L

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/queue/admin/$storeId/size")
            .exchange()
            .expectStatus().isOk

        coVerify { storeAccess.requireStoreAccess(any(), storeId) }
    }

    @Test
    fun `GET size returns 403 when store access is denied`() {
        // ForbiddenException is HttpException(FORBIDDEN); the global @RestControllerAdvice maps it to 403.
        coEvery { storeAccess.requireStoreAccess(any(), storeId) } throws
            com.thomas.notiguide.core.exception.ForbiddenException("forbidden")

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/queue/admin/$storeId/size")
            .exchange()
            .expectStatus().isForbidden
    }
}
