package com.thomas.notiguide.core.jwt

import com.auth0.jwt.interfaces.DecodedJWT
import com.thomas.notiguide.core.exception.UnverifiedAdminException
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JWTToPrincipal(
    private val adminRepository: AdminRepository
) {

    suspend fun convert(jwt: DecodedJWT): AdminPrincipal {
        val id = runCatching { UUID.fromString(jwt.subject) }
            .getOrElse { throw BadCredentialsException("Invalid JWT token") }
        val admin = adminRepository.findById(id)
            ?: throw BadCredentialsException("Your account no longer exists. Please contact an administrator.")
        if (!admin.isVerified)
            throw UnverifiedAdminException()
        return AdminPrincipal(
            _id = admin.id!!,
            _username = admin.username,
            _password = admin.passwordHash,
            _authorities = listOf(SimpleGrantedAuthority(admin.role.name)),
            storeId = admin.storeId,
            isVerified = true
        )
    }
}