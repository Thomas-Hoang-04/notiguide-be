package com.thomas.notiguide.core.device

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object DeviceSignatureVerifier {
    fun verify(publicKeyDer: ByteArray, canonical: String, signatureB64: String): Boolean {
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        }.getOrNull() as? ECPublicKey ?: return false
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureB64) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(canonical.toByteArray(Charsets.UTF_8))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }
}
