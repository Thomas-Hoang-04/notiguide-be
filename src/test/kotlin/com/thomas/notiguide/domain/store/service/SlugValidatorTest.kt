package com.thomas.notiguide.domain.store.service

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SlugValidatorTest {
    @Test
    fun `accepts a valid hyphenated slug`() {
        assertThatCode { SlugValidator.validate("my-store-2") }.doesNotThrowAnyException()
    }

    @Test
    fun `rejects a slug shorter than the minimum`() {
        assertThatThrownBy { SlugValidator.validate("ab") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a slug longer than the maximum`() {
        assertThatThrownBy { SlugValidator.validate("a".repeat(SlugValidator.MAX_LENGTH + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects leading, trailing, and doubled hyphens`() {
        assertThatThrownBy { SlugValidator.validate("-store") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SlugValidator.validate("store-") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SlugValidator.validate("a--b") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a reserved word (case-insensitive)`() {
        assertThatThrownBy { SlugValidator.validate("Admin") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
