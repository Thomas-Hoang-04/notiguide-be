package com.thomas.notiguide.domain.admin.controller

import com.thomas.notiguide.core.exception.UnverifiedAdminException
import com.thomas.notiguide.core.jwt.JWTManager
import com.thomas.notiguide.domain.admin.dto.LoginResponse
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.LoginRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val adminRepository: AdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtManager: JWTManager
) {

    @PostMapping("/login")
    suspend fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        val admin = adminRepository.findByUsername(request.username)
            ?: throw BadCredentialsException("Invalid username or password")

        if (!passwordEncoder.matches(request.password, admin.passwordHash))
            throw BadCredentialsException("Invalid username or password")

        if (!admin.isVerified)
            throw UnverifiedAdminException()

        val token = jwtManager.issue(admin.id!!, listOf(admin.role.name))
        return ResponseEntity.ok(LoginResponse(token = token, admin = admin.toDto()))
    }
}
