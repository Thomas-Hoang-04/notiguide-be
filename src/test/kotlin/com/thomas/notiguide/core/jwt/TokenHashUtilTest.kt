package com.thomas.notiguide.core.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenHashUtilTest {
    @Test
    fun `sha256 is stable for equal input`() {
        assertThat(TokenHashUtil.sha256("token-abc")).isEqualTo(TokenHashUtil.sha256("token-abc"))
    }

    @Test
    fun `sha256 differs for different input`() {
        assertThat(TokenHashUtil.sha256("a")).isNotEqualTo(TokenHashUtil.sha256("b"))
    }

    @Test
    fun `sha256 returns lowercase hex of length 64`() {
        // Lowercase hex SHA-256, consistent with key names like enrollmentToken(sha256Hex).
        assertThat(TokenHashUtil.sha256("x")).matches("[0-9a-f]{64}")
    }
}
