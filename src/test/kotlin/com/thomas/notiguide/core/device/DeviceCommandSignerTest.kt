package com.thomas.notiguide.core.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class DeviceCommandSignerTest {
    private val loader = DefaultResourceLoader()
    private val signer = DeviceCommandSigner("classpath:rsa/test-sign.pem", loader)

    private fun loadPublicKey() = loader.getResource("classpath:rsa/test-sign-pub.pem")
        .inputStream.bufferedReader().readText()
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace(Regex("\\s"), "")
        .let { Base64.getDecoder().decode(it) }
        .let { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(it)) }

    @Test
    fun `sign produces a signature that verifies with the matching public key`() {
        val canonical = "activate-v1|abc|nonce|2026-06-08T10:00:00Z|2026-06-08T11:00:00Z"
        val signatureB64 = signer.sign(canonical)
        assertThat(signatureB64).isNotBlank()

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(loadPublicKey())
            update(canonical.toByteArray(Charsets.UTF_8))
        }
        assertThat(verifier.verify(Base64.getDecoder().decode(signatureB64))).isTrue()
    }

    @Test
    fun `a signature does not verify against a different message`() {
        val signatureB64 = signer.sign("message-one")
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(loadPublicKey())
            update("message-two".toByteArray(Charsets.UTF_8))
        }
        assertThat(verifier.verify(Base64.getDecoder().decode(signatureB64))).isFalse()
    }
}
