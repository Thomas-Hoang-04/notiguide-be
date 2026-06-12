package com.thomas.notiguide.domain.queue.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.firebase.FcmNotificationService
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.service.ServiceTypeService
import com.thomas.notiguide.domain.store.service.StoreService
import com.thomas.notiguide.support.TestSecurityConfig
import io.mockk.coEvery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@WebFluxTest(
    controllers = [QueuePublicController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class QueuePublicControllerTest {
    @MockkBean lateinit var queueService: QueueService
    @MockkBean lateinit var storeService: StoreService
    @MockkBean(relaxed = true) lateinit var serviceTypeService: ServiceTypeService
    @MockkBean(relaxed = true) lateinit var fcmNotificationService: FcmNotificationService

    @Autowired lateinit var client: WebTestClient

    private fun storeDto() = StoreDto(
        id = UUID.randomUUID(),
        publicId = "pub-1",
        orgId = null,
        name = "Test Store",
        address = null,
        isActive = true,
        allowJumpCall = false,
        allowNoShow = false,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `GET size returns 200 for a public store`() {
        coEvery { storeService.getStoreByPublicId(any()) } returns storeDto()
        coEvery { queueService.getQueueSize(any()) } returns 3L

        client.get().uri("/api/queue/public/pub-1/size")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `POST fcm-token with a blank token is rejected with 400`() {
        // Decodes into RegisterFcmTokenRequest (blank token) then fails @Valid @NotBlank → 400.
        client.post().uri("/api/queue/public/pub-1/tickets/${UUID.randomUUID()}/fcm-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"token":""}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}
