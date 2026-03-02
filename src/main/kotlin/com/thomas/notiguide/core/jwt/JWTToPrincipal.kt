package com.thomas.notiguide.core.jwt

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.UUID

@Component
class JWTToPrincipal(
    // admin repo
) {
    private fun extractAuthClaim(jwt: DecodedJWT)
            : List<SimpleGrantedAuthority> {
        val claim: Claim = jwt.getClaim("auth")
        if (claim.isNull || claim.isMissing) return emptyList()
        return claim.asList(SimpleGrantedAuthority::class.java)
    }

    // TODO: convert to principal
}