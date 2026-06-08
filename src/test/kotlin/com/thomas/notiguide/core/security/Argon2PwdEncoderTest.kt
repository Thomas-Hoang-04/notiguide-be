package com.thomas.notiguide.core.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Argon2PwdEncoderTest {
    private val encoder = Argon2PwdEncoder().passwordEncoder()

    @Test
    fun `encodes and verifies the same password`() {
        val hash = encoder.encode("s3cret!")
        assertThat(encoder.matches("s3cret!", hash)).isTrue()
    }

    @Test
    fun `rejects a wrong password`() {
        val hash = encoder.encode("s3cret!")
        assertThat(encoder.matches("wrong", hash)).isFalse()
    }
}
