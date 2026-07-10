package com.thomas.notiguide.core.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class DeviceSignatureVerifierTest {
    @Suppress("SameParameterValue")
    private fun sign(privateKey: PrivateKey, canonical: String): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
        }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    @Test
    fun `verify accepts a valid signature and rejects tampering`() {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val canonical = "roster-update-v1|hub-1|7|1:433M:Table 1"
        val sigB64 = sign(kp.private, canonical)
        val der = kp.public.encoded // X.509 SubjectPublicKeyInfo DER

        assertThat(DeviceSignatureVerifier.verify(der, canonical, sigB64)).isTrue()
        assertThat(DeviceSignatureVerifier.verify(der, canonical + "x", sigB64)).isFalse()
        assertThat(DeviceSignatureVerifier.verify(der, canonical, "not-base64!!")).isFalse()
        assertThat(DeviceSignatureVerifier.verify(ByteArray(0), canonical, sigB64)).isFalse()
    }
}
