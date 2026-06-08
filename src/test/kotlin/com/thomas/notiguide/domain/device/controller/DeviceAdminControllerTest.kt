package com.thomas.notiguide.domain.device.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.device.response.DeviceListResponse
import com.thomas.notiguide.support.TestPrincipals
import com.thomas.notiguide.support.TestSecurityConfig
import io.mockk.coEvery
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
    controllers = [DeviceAdminController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class DeviceAdminControllerTest {
    @MockkBean lateinit var deviceQueryService: com.thomas.notiguide.domain.device.service.DeviceQueryService
    @MockkBean(relaxed = true) lateinit var passiveDeviceRegistrationService: com.thomas.notiguide.domain.device.service.PassiveDeviceRegistrationService
    @MockkBean(relaxed = true) lateinit var deviceApprovalService: com.thomas.notiguide.domain.device.service.DeviceApprovalService
    @MockkBean(relaxed = true) lateinit var rfCodeService: com.thomas.notiguide.domain.device.service.RfCodeService
    @MockkBean(relaxed = true) lateinit var deviceLifecycleService: com.thomas.notiguide.domain.device.service.DeviceLifecycleService
    @MockkBean(relaxed = true) lateinit var usbDispatchPayloadService: com.thomas.notiguide.domain.device.service.UsbDispatchPayloadService
    @MockkBean(relaxed = true) lateinit var hubDiagnosticsService: com.thomas.notiguide.domain.device.service.HubDiagnosticsService
    @MockkBean lateinit var storeAccess: com.thomas.notiguide.shared.principal.StoreAccessService

    @Autowired lateinit var client: WebTestClient

    @Test
    fun `GET devices returns 200 when store access is granted`() {
        val storeId = UUID.randomUUID()
        coEvery { storeAccess.requireStoreAccess(any(), any()) } returns Unit
        coEvery { deviceQueryService.listDevices(any(), any(), any()) } returns DeviceListResponse(devices = emptyList())

        client.mutateWith(mockAuthentication(TestPrincipals.authToken(storeId = storeId)))
            .get().uri("/api/devices?storeId=$storeId")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `POST passive with an invalid body is rejected with 400`() {
        // Decodes (kind + storeId present) but blank assignedName/rfCodeHex fail @Valid @NotBlank → 400.
        client.mutateWith(mockAuthentication(TestPrincipals.authToken()))
            .post().uri("/api/devices/passive")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"kind":"RECEIVER_433M_PASSIVE","assignedName":"","storeId":"${UUID.randomUUID()}","rfCodeHex":"","rfCodeBits":0}""",
            )
            .exchange()
            .expectStatus().isBadRequest
    }
}
