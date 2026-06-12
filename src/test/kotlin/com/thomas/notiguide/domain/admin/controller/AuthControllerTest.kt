package com.thomas.notiguide.domain.admin.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.jwt.JWTManager
import com.thomas.notiguide.core.jwt.LoginAbortService
import com.thomas.notiguide.core.jwt.RefreshTokenService
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.response.InviteResolveResponse
import com.thomas.notiguide.domain.admin.service.AdminService
import com.thomas.notiguide.domain.admin.service.InviteLinkService
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.service.RegistrationService
import com.thomas.notiguide.domain.admin.service.SessionService
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.support.TestSecurityConfig
import io.mockk.coEvery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(
    controllers = [AuthController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class AuthControllerTest {

    // AuthController's 13 collaborators — mocked so the slice context starts.
    @MockkBean(relaxed = true) lateinit var adminRepository: AdminRepository
    @MockkBean(relaxed = true) lateinit var passwordEncoder: PasswordEncoder
    @MockkBean(relaxed = true) lateinit var jwtManager: JWTManager
    @MockkBean(relaxed = true) lateinit var refreshTokenService: RefreshTokenService
    @MockkBean(relaxed = true) lateinit var storeRepository: StoreRepository
    @MockkBean(relaxed = true) lateinit var jwtProperties: JWTProperties
    @MockkBean(relaxed = true) lateinit var appProperties: AppProperties
    @MockkBean(relaxed = true) lateinit var adminService: AdminService
    @MockkBean(relaxed = true) lateinit var sessionService: SessionService
    @MockkBean(relaxed = true) lateinit var loginAbortService: LoginAbortService
    @MockkBean(relaxed = true) lateinit var registrationService: RegistrationService
    @MockkBean(relaxed = true) lateinit var joinRequestService: JoinRequestService
    @MockkBean(relaxed = true) lateinit var inviteLinkService: InviteLinkService

    @Autowired
    lateinit var client: WebTestClient

    @Test
    fun `POST login with a blank body is rejected with 400`() {
        // Decodes into LoginRequest (blank strings) then fails @Valid @NotBlank → WebExchangeBindException → 400.
        // (A fully empty body would fail Kotlin non-null decoding and surface as the catch-all 500 instead.)
        client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"","password":""}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `GET invite resolve returns the target display info for a valid token`() {
        coEvery { inviteLinkService.resolveForDisplay("i_tok") } returns
            InviteResolveResponse(targetType = "STORE", name = "Acme Store")

        client.get().uri("/api/auth/invite/i_tok")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.targetType").isEqualTo("STORE")
            .jsonPath("$.name").isEqualTo("Acme Store")
    }

    @Test
    fun `GET invite resolve returns 404 for an unknown or expired token`() {
        coEvery { inviteLinkService.resolveForDisplay("i_dead") } returns null

        client.get().uri("/api/auth/invite/i_dead")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("Invite link is invalid or has expired")
    }
}
