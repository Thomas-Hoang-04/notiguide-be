package com.thomas.notiguide.core.device

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.springframework.core.io.ResourceLoader
import java.io.StringReader
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.util.Base64

class DeviceCommandSigner(
    keyLocation: String,
    resourceLoader: ResourceLoader
) {

    private val privateKey: ECPrivateKey = loadPrivateKey(keyLocation, resourceLoader)

    fun sign(canonical: String): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun loadPrivateKey(
        location: String,
        resourceLoader: ResourceLoader
    ): ECPrivateKey {
        val pemText = if (location.startsWith("base64:")) {
            val encoded = location.removePrefix("base64:")
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } else {
            resourceLoader.getResource(location).inputStream.bufferedReader().use { it.readText() }
        }

        val converter = JcaPEMKeyConverter()
        val privateKey = PEMParser(StringReader(pemText)).use { parser ->
            when (val parsed = parser.readObject()) {
                is PEMKeyPair -> converter.getKeyPair(parsed).private
                is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                else -> throw IllegalStateException("Unsupported EC private key PEM content: ${parsed?.javaClass?.name ?: "empty"}")
            }
        }

        val ecPrivateKey = privateKey as? ECPrivateKey
            ?: throw IllegalStateException("Device command-signing key must be an EC private key")
        require(ecPrivateKey.params.order.bitLength() == 256) {
            "Device command-signing key must target the P-256 / secp256r1 curve"
        }
        return ecPrivateKey
    }
}
