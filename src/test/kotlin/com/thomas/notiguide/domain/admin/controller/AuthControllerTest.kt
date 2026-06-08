package com.thomas.notiguide.domain.admin.controller

import com.ninjasquad.springmockk.MockkBean
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.support.TestSecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(
    controllers = [AuthController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(TestSecurityConfig::class)
class AuthControllerTest {

    // AuthController's 12 collaborators — mocked so the slice context starts.
    @MockkBean(relaxed = true) lateinit var adminRepository: com.thomas.notiguide.domain.admin.repository.AdminRepository
    @MockkBean(relaxed = true) lateinit var passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder
    @MockkBean(relaxed = true) lateinit var jwtManager: com.thomas.notiguide.core.jwt.JWTManager
    @MockkBean(relaxed = true) lateinit var refreshTokenService: com.thomas.notiguide.core.jwt.RefreshTokenService
    @MockkBean(relaxed = true) lateinit var storeRepository: com.thomas.notiguide.domain.store.repository.StoreRepository
    @MockkBean(relaxed = true) lateinit var jwtProperties: com.thomas.notiguide.core.config.JWTProperties
    @MockkBean(relaxed = true) lateinit var appProperties: com.thomas.notiguide.core.config.AppProperties
    @MockkBean(relaxed = true) lateinit var adminService: com.thomas.notiguide.domain.admin.service.AdminService
    @MockkBean(relaxed = true) lateinit var sessionService: com.thomas.notiguide.domain.admin.service.SessionService
    @MockkBean(relaxed = true) lateinit var loginAbortService: com.thomas.notiguide.core.jwt.LoginAbortService
    @MockkBean(relaxed = true) lateinit var registrationService: com.thomas.notiguide.domain.admin.service.RegistrationService
    @MockkBean(relaxed = true) lateinit var joinRequestService: com.thomas.notiguide.domain.admin.service.JoinRequestService

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
}
