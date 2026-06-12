package com.thomas.notiguide.core.tenant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InviteTokenGeneratorTest {
    @Test
    fun `token carries the invite prefix`() {
        assertThat(InviteTokenGenerator.generate(16)).startsWith("i_")
    }

    @Test
    fun `a 16-byte token is 24 chars total`() {
        // 16 bytes → 22 base64url chars (no padding) + the 2-char prefix
        assertThat(InviteTokenGenerator.generate(16)).hasSize(24)
    }

    @Test
    fun `tokens are distinct across calls`() {
        val a = InviteTokenGenerator.generate(16)
        val b = InviteTokenGenerator.generate(16)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `token suffix is url-safe`() {
        val suffix = InviteTokenGenerator.generate(16).removePrefix("i_")
        assertThat(suffix).matches("[A-Za-z0-9_-]+")
    }
}
