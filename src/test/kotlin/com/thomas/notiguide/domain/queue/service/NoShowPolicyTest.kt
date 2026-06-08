package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.entity.StoreSettings
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NoShowPolicyTest {
    private val settings = StoreSettings(storeId = UUID.randomUUID())

    @Test
    fun `returns settings when the store allows no-show`() {
        val store = mockk<Store> { every { allowNoShow } returns true }
        assertThat(resolveApplicableNoShowSettings(store, settings)).isSameAs(settings)
    }

    @Test
    fun `returns null when the store disallows no-show`() {
        val store = mockk<Store> { every { allowNoShow } returns false }
        assertThat(resolveApplicableNoShowSettings(store, settings)).isNull()
    }

    @Test
    fun `returns null when the store is null`() {
        assertThat(resolveApplicableNoShowSettings(null, settings)).isNull()
    }

    @Test
    fun `returns null when settings is null`() {
        val store = mockk<Store> { every { allowNoShow } returns true }
        assertThat(resolveApplicableNoShowSettings(store, null)).isNull()
    }
}
