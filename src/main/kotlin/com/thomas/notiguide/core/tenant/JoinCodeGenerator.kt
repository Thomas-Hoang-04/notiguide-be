package com.thomas.notiguide.core.tenant

import java.security.SecureRandom
import java.util.Base64

object JoinCodeGenerator {
    const val ORG_PREFIX = "o_"
    const val STORE_PREFIX = "s_"

    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** e.g. "o_X1k9..." — 9 random bytes → 12 url-safe chars after the prefix. */
    fun generate(prefix: String): String {
        val bytes = ByteArray(9)
        secureRandom.nextBytes(bytes)
        return prefix + encoder.encodeToString(bytes)
    }
}
