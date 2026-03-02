package com.thomas.notiguide.core.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.thomas.notiguide.core.security.RSAKeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class JWTManager {
    suspend fun issue(id: UUID, roles: List<String>): String
        = withContext(Dispatchers.Default) {
            JWT.create()
                .withSubject(id.toString())
                .withExpiresAt(Instant.now().plusSeconds(120))
                .withClaim("auth", roles)
                .sign(Algorithm.RSA512(RSAKeyProperties.publicKey, RSAKeyProperties.privateKey))
        }

    suspend fun verify(token: String): DecodedJWT
        = withContext(Dispatchers.Default) {
            JWT.require(
                Algorithm.RSA512(RSAKeyProperties.publicKey, RSAKeyProperties.privateKey)
            ).build().verify(token)
        }
}