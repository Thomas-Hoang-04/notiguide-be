package com.thomas.notiguide.core.tenant

import java.security.SecureRandom
import java.util.Base64

object InviteTokenGenerator {
    const val PREFIX = "i_"

    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** e.g. "i_…" — byteCount random bytes, base64url without padding, after the prefix. */
    fun generate(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return PREFIX + encoder.encodeToString(bytes)
    }
}
