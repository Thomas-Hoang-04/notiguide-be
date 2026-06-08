package com.thomas.notiguide.core.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DeviceCanonicalTest {
    private val challengeId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val issued = OffsetDateTime.of(2026, 6, 8, 10, 0, 0, 0, ZoneOffset.UTC)
    private val expires = OffsetDateTime.of(2026, 6, 8, 11, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `activate canonical has the exact documented format`() {
        assertThat(DeviceCanonical.activate(challengeId, "nonce-1", issued, expires))
            .isEqualTo("activate-v1|$challengeId|nonce-1|${issued.toInstant()}|${expires.toInstant()}")
    }

    @Test
    fun `activate canonical is stable for equal input`() {
        assertThat(DeviceCanonical.activate(challengeId, "n", issued, expires))
            .isEqualTo(DeviceCanonical.activate(challengeId, "n", issued, expires))
    }

    @Test
    fun `activate canonical differs when the nonce changes`() {
        assertThat(DeviceCanonical.activate(challengeId, "n1", issued, expires))
            .isNotEqualTo(DeviceCanonical.activate(challengeId, "n2", issued, expires))
    }
}
