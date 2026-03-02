package com.thomas.notiguide.core.security

import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

@Component
object RSAKeyProperties {
    private val _publicKey: RSAPublicKey
    val publicKey: RSAPublicKey
        get() = _publicKey
    private val _privateKey: RSAPrivateKey
    val privateKey: RSAPrivateKey
        get() = _privateKey

    init {
        val keyPair: KeyPair = KeyGenerator.generateRSAKey()
        this._publicKey = keyPair.public as RSAPublicKey
        this._privateKey = keyPair.private as RSAPrivateKey
    }
}