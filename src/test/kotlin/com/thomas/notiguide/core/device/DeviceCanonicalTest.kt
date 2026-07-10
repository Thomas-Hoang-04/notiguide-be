package com.thomas.notiguide.core.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DeviceCanonicalTest {
    @Test
    fun `activate binds registration_nonce as the first field`() {
        val issued = OffsetDateTime.parse("2026-07-09T09:00:00Z")
        val expires = OffsetDateTime.parse("2026-07-09T09:05:00Z")
        val result = DeviceCanonical.activate("Ab-_nonce123", "bWFjbm9uY2U", issued, expires)
        assertThat(result)
            .isEqualTo("activate-v1|Ab-_nonce123|bWFjbm9uY2U|2026-07-09T09:00:00Z|2026-07-09T09:05:00Z")
    }

    @Test
    fun `activate canonical is stable for equal input`() {
        val issued = OffsetDateTime.parse("2026-07-09T09:00:00Z")
        val expires = OffsetDateTime.parse("2026-07-09T09:05:00Z")
        val first = DeviceCanonical.activate("Ab-_nonce123", "bWFjbm9uY2U", issued, expires)
        val second = DeviceCanonical.activate("Ab-_nonce123", "bWFjbm9uY2U", issued, expires)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `activate canonical differs when the nonce changes`() {
        val issued = OffsetDateTime.parse("2026-07-09T09:00:00Z")
        val expires = OffsetDateTime.parse("2026-07-09T09:05:00Z")
        val base = DeviceCanonical.activate("Ab-_nonce123", "bWFjbm9uY2U", issued, expires)
        val differentNonce = DeviceCanonical.activate("Ab-_nonce123", "b3RoZXJub25jZQ", issued, expires)
        val differentRegistrationNonce = DeviceCanonical.activate("Different-nonce456", "bWFjbm9uY2U", issued, expires)
        assertThat(base).isNotEqualTo(differentNonce)
        assertThat(base).isNotEqualTo(differentRegistrationNonce)
    }

    @Test
    fun `rosterUpdate sorts receivers by slot and inlines slot colon band colon label`() {
        val receivers = listOf(
            DeviceCanonical.RosterCanonicalReceiver(2, "2.4G", ""),
            DeviceCanonical.RosterCanonicalReceiver(1, "433M", "Table 1")
        )
        assertThat(DeviceCanonical.rosterUpdate("hub-9", 7, receivers))
            .isEqualTo("roster-update-v1|hub-9|7|1:433M:Table 1|2:2.4G:")
        assertThat(DeviceCanonical.rosterUpdate("hub-9", 0, emptyList()))
            .isEqualTo("roster-update-v1|hub-9|0")
    }

    @Test
    fun `ack and heartbeat canonicals`() {
        assertThat(DeviceCanonical.ack("hub-9", "transmit", "d-uuid", "applied"))
            .isEqualTo("ack-v1|hub-9|transmit|d-uuid|applied")
        assertThat(DeviceCanonical.heartbeat("hub-9", "2026-07-09T09:15:00Z", 42, "-58", 123456L, 12L, 340L, "192.168.1.50"))
            .isEqualTo("heartbeat-v1|hub-9|2026-07-09T09:15:00Z|42|-58|123456|12|340|192.168.1.50")
        assertThat(DeviceCanonical.heartbeat("hub-9", "2026-07-09T09:15:00Z", 0, "", 5L, 0L, 0L, ""))
            .isEqualTo("heartbeat-v1|hub-9|2026-07-09T09:15:00Z|0||5|0|0|")
    }
}
