package com.thomas.notiguide.core.jwt

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JWTToPrincipal(
    private val adminRepository: AdminRepository
) {
    private fun extractAuthClaim(jwt: DecodedJWT): List<SimpleGrantedAuthority> {
        val claim: Claim = jwt.getClaim("auth")
        if (claim.isNull || claim.isMissing) return emptyList()
        return claim.asList(String::class.java).map { SimpleGrantedAuthority(it) }
    }

    suspend fun convert(jwt: DecodedJWT): AdminPrincipal? {
        val id = UUID.fromString(jwt.subject) ?: return null
        val admin = adminRepository.findById(id) ?: return null
        return AdminPrincipal(
            _id = admin.id!!,
            _username = admin.username,
            _password = admin.passwordHash,
            _authorities = extractAuthClaim(jwt),
            storeId = admin.storeId,
            isVerified = admin.isVerified
        )
    }
}