package com.thomas.notiguide.domain.admin.controller

import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.PendingJoinRequestException
import com.thomas.notiguide.core.exception.UnverifiedAdminException
import com.thomas.notiguide.core.jwt.JWTManager
import com.thomas.notiguide.core.jwt.LoginAbortService
import com.thomas.notiguide.core.jwt.RefreshTokenService
import com.thomas.notiguide.core.jwt.TokenHashUtil
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.AbortLoginRequest
import com.thomas.notiguide.domain.admin.request.LoginRequest
import com.thomas.notiguide.domain.admin.request.RegisterRequest
import com.thomas.notiguide.domain.admin.response.InviteResolveResponse
import com.thomas.notiguide.domain.admin.response.LoginResponse
import com.thomas.notiguide.domain.admin.types.RegisterStatus
import com.thomas.notiguide.domain.admin.response.RegisterResponse
import com.thomas.notiguide.domain.admin.service.AdminService
import com.thomas.notiguide.domain.admin.service.InviteLinkService
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.service.RegistrationService
import com.thomas.notiguide.domain.admin.service.SessionService
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.http.ClientIpResolver
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val adminRepository: AdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtManager: JWTManager,
    private val refreshTokenService: RefreshTokenService,
    private val storeRepository: StoreRepository,
    private val jwtProperties: JWTProperties,
    private val appProperties: AppProperties,
    private val adminService: AdminService,
    private val sessionService: SessionService,
    private val loginAbortService: LoginAbortService,
    private val registrationService: RegistrationService,
    private val joinRequestService: JoinRequestService,
    private val inviteLinkService: InviteLinkService
) {

    @PostMapping("/register")
    suspend fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<RegisterResponse> {
        val response = registrationService.register(request)
        val status = if (response.status == RegisterStatus.PENDING) HttpStatus.ACCEPTED else HttpStatus.CREATED
        return ResponseEntity.status(status).body(response)
    }

    @GetMapping("/invite/{token}")
    suspend fun resolveInvite(@PathVariable token: String): ResponseEntity<InviteResolveResponse> {
        // Public, read-only, never mints or consumes. Unknown and expired are
        // indistinguishable by design; the message never echoes the token.
        val resolved = inviteLinkService.resolveForDisplay(token)
            ?: throw HttpException(HttpStatus.NOT_FOUND, "Invite link is invalid or has expired")
        return ResponseEntity.ok(resolved)
    }

    @PostMapping("/login")
    suspend fun login(
        @Valid @RequestBody request: LoginRequest,
        serverRequest: ServerHttpRequest
    ): ResponseEntity<LoginResponse> {
        val ip = extractClientIp(serverRequest)
        val admin = adminRepository.findByUsername(request.username.trim())
            ?: run {
                val pending = joinRequestService.findPendingByUsername(request.username.trim().lowercase())
                if (pending != null && passwordEncoder.matches(request.password, pending.passwordHash)) {
                    throw PendingJoinRequestException()
                }
                throw BadCredentialsException("Invalid username or password")
            }

        if (!passwordEncoder.matches(request.password, admin.passwordHash)) {
            adminService.recordLoginAttempt(admin.id!!, ip, success = false)
            throw BadCredentialsException("Invalid username or password")
        }

        if (!admin.isVerified) {
            adminService.recordLoginAttempt(admin.id!!, ip, success = false)
            throw UnverifiedAdminException()
        }

        val loginHistoryRow = adminService.recordLoginAttempt(admin.id!!, ip, success = true)
        val loginHistoryId = loginHistoryRow.id
            ?: throw IllegalStateException("Saved login history row is missing its id")

        val storeName = admin.storeId?.let { storeRepository.findById(it)?.name }
        val accessToken = jwtManager.issue(admin.id, listOf(admin.role.name))
        val refreshToken = refreshTokenService.issue(admin.id)

        val tokenHash = TokenHashUtil.sha256(accessToken)
        val userAgent = serverRequest.headers.getFirst("User-Agent")
        val session = sessionService.createSession(admin.id, tokenHash, ip, userAgent)

        // Mint a one-shot abort token so the client can roll back this
        // session/refresh-token/login-history triple if its post-login
        // verification ping reveals the Set-Cookie did not stick.
        // If we cannot provision that rollback capability, we clean up the
        // just-created artifacts and fail the login instead of returning a
        // "successful" response with no recovery path.
        val abortToken = try {
            loginAbortService.issue(tokenHash, refreshToken, loginHistoryId)
        } catch (ex: Exception) {
            runCatching {
                loginAbortService.rollbackArtifacts(tokenHash, refreshToken, loginHistoryId)
            }.onFailure { rollbackEx ->
                ex.addSuppressed(rollbackEx)
            }
            throw IllegalStateException("Failed to provision login rollback token", ex)
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, buildAccessCookie(accessToken).toString())
            .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(refreshToken).toString())
            .body(
                LoginResponse(
                    admin = admin.toDto(storeName),
                    sessionId = session.id?.toString(),
                    abortToken = abortToken
                )
            )
    }

    @PostMapping("/abort")
    suspend fun abort(@Valid @RequestBody request: AbortLoginRequest): ResponseEntity<Void> {
        // Public endpoint — body-authenticated by the one-shot opaque abort
        // token (Redis-backed, ~60s TTL). Returns 204 regardless of whether
        // the token matched, to avoid leaking timing/oracle signal about
        // valid vs invalid tokens to a probing client.
        loginAbortService.consume(request.abortToken)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clearCookie(appProperties.cookie.name, appProperties.cookie.path).toString())
            .header(HttpHeaders.SET_COOKIE, clearCookie(appProperties.cookie.refreshName, appProperties.cookie.refreshPath).toString())
            .build()
    }

    @PostMapping("/refresh")
    suspend fun refresh(request: ServerHttpRequest): ResponseEntity<Void> {
        val refreshToken = extractRefreshToken(request)
            ?: throw BadCredentialsException("Missing refresh token")

        val (adminId, newRefreshToken) = refreshTokenService.rotate(refreshToken)
            ?: throw BadCredentialsException("Invalid or expired refresh token")

        val admin = adminRepository.findById(adminId)
            ?: throw BadCredentialsException("Your account no longer exists")

        if (!admin.isVerified)
            throw UnverifiedAdminException()

        // Clean up old session keyed by old access token
        val oldAccessToken = extractAccessToken(request)
        if (oldAccessToken != null) {
            sessionService.deleteByTokenHash(TokenHashUtil.sha256(oldAccessToken))
        }

        val accessToken = jwtManager.issue(admin.id!!, listOf(admin.role.name))

        // Create new session for the new access token
        val tokenHash = TokenHashUtil.sha256(accessToken)
        val ip = extractClientIp(request)
        val userAgent = request.headers.getFirst("User-Agent")
        sessionService.createSession(admin.id, tokenHash, ip, userAgent)

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, buildAccessCookie(accessToken).toString())
            .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(newRefreshToken).toString())
            .build()
    }

    @PostMapping("/logout")
    suspend fun logout(request: ServerHttpRequest): ResponseEntity<Void> {
        extractRefreshToken(request)?.let { refreshTokenService.revoke(it) }

        val accessToken = extractAccessToken(request)
        if (accessToken != null) {
            val tokenHash = TokenHashUtil.sha256(accessToken)
            sessionService.deleteByTokenHash(tokenHash)
        }

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clearCookie(appProperties.cookie.name, appProperties.cookie.path).toString())
            .header(HttpHeaders.SET_COOKIE, clearCookie(appProperties.cookie.refreshName, appProperties.cookie.refreshPath).toString())
            .build()
    }

    private fun extractClientIp(request: ServerHttpRequest): String =
        ClientIpResolver.resolve(request)

    private fun extractAccessToken(request: ServerHttpRequest): String? {
        val authHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader != null && authHeader.startsWith("Bearer "))
            return authHeader.substring(7)
        return request.cookies.getFirst(appProperties.cookie.name)
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractRefreshToken(request: ServerHttpRequest): String? =
        request.cookies.getFirst(appProperties.cookie.refreshName)
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun buildAccessCookie(token: String): ResponseCookie =
        buildCookie(appProperties.cookie.name, token, jwtProperties.accessExpirySeconds, appProperties.cookie.path)

    private fun buildRefreshCookie(token: String): ResponseCookie =
        buildCookie(appProperties.cookie.refreshName, token, jwtProperties.refreshExpirySeconds, appProperties.cookie.refreshPath)

    private fun clearCookie(name: String, path: String): ResponseCookie =
        buildCookie(name, "", 0, path)

    private fun buildCookie(name: String, value: String, maxAge: Long, path: String): ResponseCookie {
        val cookieProperties = appProperties.cookie
        val builder = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .sameSite(cookieProperties.sameSite)
            .path(path)
            .maxAge(maxAge)

        cookieProperties.domain
            ?.takeIf { it.isNotBlank() }
            ?.let(builder::domain)

        return builder.build()
    }
}
