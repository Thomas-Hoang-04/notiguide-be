package com.thomas.notiguide.core.tenant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JoinCodeGeneratorTest {
    @Test
    fun `org code carries the org prefix`() {
        assertThat(JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX)).startsWith("o_")
    }

    @Test
    fun `store code carries the store prefix`() {
        assertThat(JoinCodeGenerator.generate(JoinCodeGenerator.STORE_PREFIX)).startsWith("s_")
    }

    @Test
    fun `codes are distinct across calls`() {
        val a = JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX)
        val b = JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `suffix is url-safe`() {
        val suffix = JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX).removePrefix("o_")
        assertThat(suffix).matches("[A-Za-z0-9_-]+")
    }
}
