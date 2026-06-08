package com.thomas.notiguide.core.jwt

import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.core.security.RSAKeyProperties
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

class JWTManagerTest {
    private val props = JWTProperties(
        accessExpirySeconds = 900,
        refreshExpirySeconds = 604800,
        privateKey = "unused",
        privateKeyPassword = "",
        publicKey = "unused",
    )

    private fun managerWithFreshKeys(): JWTManager {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keys = mockk<RSAKeyProperties> {
            every { publicKey } returns pair.public as RSAPublicKey
            every { privateKey } returns pair.private as RSAPrivateKey
        }
        return JWTManager(props, keys)
    }

    @Test
    fun `issue then verify round-trips the subject`() = runTest {
        val manager = managerWithFreshKeys()
        val id = UUID.randomUUID()
        val token = manager.issue(id, listOf("ROLE_ADMIN"))
        val decoded = manager.verify(token)
        assertThat(decoded.subject).isEqualTo(id.toString())
    }

    @Test
    fun `verify rejects a token signed by a different key`() = runTest {
        val foreignToken = managerWithFreshKeys().issue(UUID.randomUUID(), listOf("ROLE_ADMIN"))
        val thrown = runCatching { managerWithFreshKeys().verify(foreignToken) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }

    @Test
    fun `verify rejects a malformed token`() = runTest {
        val thrown = runCatching { managerWithFreshKeys().verify("not-a-jwt") }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }
}
