package com.thomas.notiguide.domain.device.listener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class HeartbeatFreshnessTest {
    private val now = OffsetDateTime.parse("2026-07-09T09:00:00Z").toInstant()

    fun heartbeatFresh(issuedAtRaw: String, now: Instant, windowSeconds: Long): Boolean {
        val issued = runCatching { OffsetDateTime.parse(issuedAtRaw).toInstant() }.getOrNull() ?: return false
        return Duration.between(issued, now).abs().seconds <= windowSeconds
    }

    @Test
    fun `accepts within window, rejects stale future and unparseable`() {
        assertThat(heartbeatFresh("2026-07-09T09:01:00Z", now, 120L)).isTrue()   // +60s
        assertThat(heartbeatFresh("2026-07-09T08:59:00Z", now, 120L)).isTrue()   // -60s
        assertThat(heartbeatFresh("2026-07-09T09:10:00Z", now, 120L)).isFalse()  // +600s
        assertThat(heartbeatFresh("2026-07-09T08:50:00Z", now, 120L)).isFalse()  // -600s
        assertThat(heartbeatFresh("not-a-timestamp", now, 120L)).isFalse()
    }
}
