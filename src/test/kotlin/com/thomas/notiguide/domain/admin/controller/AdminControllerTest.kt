package com.thomas.notiguide.domain.admin.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.types.AdminRole
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
    controllers = [AdminController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class AdminControllerTest {
    @MockkBean lateinit var adminService: com.thomas.notiguide.domain.admin.service.AdminService
    @MockkBean(relaxed = true) lateinit var sessionService: com.thomas.notiguide.domain.admin.service.SessionService
    @MockkBean(relaxed = true) lateinit var refreshTokenService: com.thomas.notiguide.core.jwt.RefreshTokenService
    @MockkBean(relaxed = true) lateinit var appProperties: com.thomas.notiguide.core.config.AppProperties
    @MockkBean(relaxed = true) lateinit var storeAccess: com.thomas.notiguide.shared.principal.StoreAccessService

    @Autowired lateinit var client: WebTestClient

    @Test
    fun `POST admins is forbidden for a non-super admin`() {
        // Body is valid (passes @Valid) so the manual requireSuperAdmin gate is what rejects with 403.
        client.mutateWith(mockAuthentication(TestPrincipals.authToken(role = AdminRole.ROLE_ADMIN)))
            .post().uri("/api/admins")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"newadmin","password":"Abcdef1!","role":"ROLE_ADMIN","storeId":null}""")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `GET me returns 200 with the current admin`() {
        coEvery { adminService.getAdmin(any()) } returns AdminDto(
            id = UUID.randomUUID(),
            username = "tester",
            role = AdminRole.ROLE_ADMIN,
            orgId = null,
            storeId = null,
            isVerified = true,
            createdBy = null,
            verifiedBy = null,
            verifiedAt = null,
            createdAt = null,
            updatedAt = null,
        )

        client.mutateWith(mockAuthentication(TestPrincipals.authToken()))
            .get().uri("/api/admins/me")
            .exchange()
            .expectStatus().isOk
    }
}
