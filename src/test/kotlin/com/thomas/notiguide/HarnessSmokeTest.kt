package com.thomas.notiguide

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HarnessSmokeTest {
    private fun interface Repo { suspend fun load(id: Int): String }

    @Test
    fun `mockk and runTest are wired correctly`() = runTest {
        val repo = mockk<Repo>()
        coEvery { repo.load(1) } returns "ok"
        assertThat(repo.load(1)).isEqualTo("ok")
    }
}
