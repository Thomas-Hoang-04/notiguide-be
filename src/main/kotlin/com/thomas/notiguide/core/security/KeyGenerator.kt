package com.thomas.notiguide.core.security

import java.security.KeyPair
import java.security.KeyPairGenerator

object KeyGenerator {
    fun generateRSAKey(): KeyPair {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(4096)
            return keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate RSA key pair", e)
        }
    }
}