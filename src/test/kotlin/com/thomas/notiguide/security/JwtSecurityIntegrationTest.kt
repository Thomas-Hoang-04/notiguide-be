package com.thomas.notiguide.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.core.jwt.JWTAuthFilter
import com.thomas.notiguide.core.jwt.JWTManager
import com.thomas.notiguide.core.jwt.JWTToPrincipal
import com.thomas.notiguide.core.ratelimit.RateLimitFilter
import com.thomas.notiguide.core.security.RSAKeyProperties
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.service.SessionService
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.shared.principal.AdminPrincipal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan.Filter
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

@WebFluxTest(
    controllers = [JwtSecurityIntegrationTest.StubController::class],
    excludeFilters = [Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [JWTAuthFilter::class, RateLimitFilter::class])],
)
@Import(JwtSecurityIntegrationTest.JwtTestConfig::class)
class JwtSecurityIntegrationTest {

    @RestController
    class StubController {
        @GetMapping("/api/queue/public/ping")
        fun publicPing(): String = "public"

        // Echoes the authenticated principal's authorities, so we can assert they came from the DB role.
        @GetMapping("/secure/ping")
        suspend fun securePing(@AuthenticationPrincipal principal: AdminPrincipal): String =
            principal.authorities.joinToString(",") { it.authority }
    }

    @TestConfiguration
    @EnableWebFluxSecurity
    class JwtTestConfig {
        // One fixed keypair shared by the JWTManager bean — used by both the filter and the test.
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        // Register the stub controller explicitly: a nested test-class @RestController is not picked up
        // by the application component scan, so its routes would otherwise never be mapped (404).
        @Bean fun stubController() = StubController()

        @Bean fun rsaKeys(): RSAKeyProperties = mockk {
            every { publicKey } returns pair.public as RSAPublicKey
            every { privateKey } returns pair.private as RSAPrivateKey
        }

        // JWTProperties is a constructor-bound @ConfigurationProperties with required fields; building
        // it inline (not as a bean) keeps the config-props binder from trying to rebind it from the
        // empty test environment, which would fail on the missing privateKey/publicKey.
        @Bean fun jwtManager(keys: RSAKeyProperties) =
            JWTManager(JWTProperties(900, 604800, "unused", "", "unused"), keys)

        @Bean fun adminRepository(): AdminRepository = mockk(relaxed = true)

        @Bean fun jwtToPrincipal(repo: AdminRepository) = JWTToPrincipal(repo)

        @Bean fun sessionService(): SessionService = mockk(relaxed = true) {
            coEvery { isRevoked(any()) } returns false
        }

        // A real AppProperties() (all defaults) supplies the cookie name the filter reads; passing it
        // inline keeps it out of the context so the config-props binder never touches it either.
        @Bean fun jwtAuthFilter(
            jwtManager: JWTManager,
            jwtToPrincipal: JWTToPrincipal,
            sessionService: SessionService,
        ) = JWTAuthFilter(AppProperties(), jwtManager, jwtToPrincipal, jacksonObjectMapper(), sessionService)

        // Mirrors SecurityConfig's rules (minus RateLimitFilter, which would need Redis).
        @Bean fun securityWebFilterChain(http: ServerHttpSecurity, jwtAuthFilter: JWTAuthFilter): SecurityWebFilterChain =
            http
                .csrf { it.disable() }
                .authorizeExchange {
                    it.pathMatchers("/api/auth/**", "/api/queue/public/**").permitAll()
                    it.anyExchange().authenticated()
                }
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build()
    }

    @Autowired lateinit var client: WebTestClient
    @Autowired lateinit var jwtManager: JWTManager
    @Autowired lateinit var adminRepository: AdminRepository

    @Test
    fun `public route is reachable without a token`() {
        client.get().uri("/api/queue/public/ping").exchange().expectStatus().isOk
    }

    @Test
    fun `secure route is 401 without a token`() {
        client.get().uri("/secure/ping").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `secure route authorizes via the DB role, not the JWT claim`() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        val admin = Admin(id = id, username = "alice", passwordHash = "h", role = AdminRole.ROLE_ADMIN, isVerified = true)
        coEvery { adminRepository.findById(id) } returns admin // convert() loads by id from the token subject

        // Token claims ROLE_SUPER_ADMIN, but convert() ignores the claim and uses admin.role.
        val token = jwtManager.issue(id, listOf("ROLE_SUPER_ADMIN"))

        client.get().uri("/secure/ping")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("ROLE_ADMIN")
    }
}
