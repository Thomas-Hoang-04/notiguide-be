package com.thomas.notiguide.core.device

import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceKind
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevicePublicIdMinterTest {
    private val repo = mockk<DeviceRepository>()
    private val minter = DevicePublicIdMinter(repo)

    @Test
    fun `mint produces a rcv-prefixed Crockford id for a receiver`() = runTest {
        coEvery { repo.existsByPublicId(any()) } returns false
        assertThat(minter.mint(DeviceKind.RECEIVER_433M)).matches("rcv-[0-9ABCDEFGHJKMNPQRSTVWXYZ]{5}")
    }

    @Test
    fun `mint uses the hub prefix for a transmitter hub`() = runTest {
        coEvery { repo.existsByPublicId(any()) } returns false
        assertThat(minter.mint(DeviceKind.TRANSMITTER_HUB)).startsWith("hub-")
    }

    @Test
    fun `mint uses the passive prefix for a passive receiver`() = runTest {
        coEvery { repo.existsByPublicId(any()) } returns false
        assertThat(minter.mint(DeviceKind.RECEIVER_433M_PASSIVE)).startsWith("pas-")
    }

    @Test
    fun `mint throws when every candidate collides`() = runTest {
        coEvery { repo.existsByPublicId(any()) } returns true
        val thrown = runCatching { minter.mint(DeviceKind.RECEIVER_2_4G) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }
}
