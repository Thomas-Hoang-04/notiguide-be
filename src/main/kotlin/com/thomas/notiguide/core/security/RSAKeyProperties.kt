package com.thomas.notiguide.core.security

import com.thomas.notiguide.core.config.JWTProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Component
class RSAKeyProperties(
    jwtProperties: JWTProperties,
    resourceLoader: ResourceLoader
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val _publicKey: RSAPublicKey
    val publicKey: RSAPublicKey
        get() = _publicKey

    private val _privateKey: RSAPrivateKey
    val privateKey: RSAPrivateKey
        get() = _privateKey

    init {
        val keyFactory = KeyFactory.getInstance("RSA")

        val privatePem = resourceLoader.getResource(jwtProperties.privateKey).inputStream.bufferedReader().readText()
        _privateKey = decryptPrivateKey(decodePem(privatePem), jwtProperties.privateKeyPassword, keyFactory)

        val publicPem = resourceLoader.getResource(jwtProperties.publicKey).inputStream.bufferedReader().readText()
        _publicKey = keyFactory.generatePublic(X509EncodedKeySpec(decodePem(publicPem))) as RSAPublicKey

        val testData = "key-pair-verification".toByteArray()
        val signature = Signature.getInstance("SHA512withRSA").apply {
            initSign(_privateKey)
            update(testData)
        }.sign()
        val verified = Signature.getInstance("SHA512withRSA").apply {
            initVerify(_publicKey)
            update(testData)
        }.verify(signature)
        require(verified) { "RSA key pair mismatch: private key and public key do not belong to the same pair" }

        logger.info("RSA key pair verified and loaded")
    }

    private fun decryptPrivateKey(encryptedBytes: ByteArray, password: String, keyFactory: KeyFactory): RSAPrivateKey {
        val encryptedInfo = EncryptedPrivateKeyInfo(encryptedBytes)
        val pbeKeySpec = PBEKeySpec(password.toCharArray())
        val secretKeyFactory = SecretKeyFactory.getInstance(encryptedInfo.algName)
        val secretKey = secretKeyFactory.generateSecret(pbeKeySpec)
        val cipher = Cipher.getInstance(encryptedInfo.algName)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, encryptedInfo.algParameters)
        val pkcs8Spec = encryptedInfo.getKeySpec(cipher)
        return keyFactory.generatePrivate(pkcs8Spec) as RSAPrivateKey
    }

    private fun decodePem(pem: String): ByteArray {
        val stripped = pem
            .replace("-----BEGIN [A-Z ]+-----".toRegex(), "")
            .replace("-----END [A-Z ]+-----".toRegex(), "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(stripped)
    }
}
