package com.thomas.notiguide.core.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class RedisTTLPolicyTest {
    @Test
    fun `ticket TTLs match the documented policy`() {
        assertThat(RedisTTLPolicy.TICKET_WAITING).isEqualTo(Duration.ofHours(12))
        assertThat(RedisTTLPolicy.TICKET_CALLED).isEqualTo(Duration.ofMinutes(30))
        assertThat(RedisTTLPolicy.TICKET_TERMINAL).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `token and join-request TTLs match the documented policy`() {
        assertThat(RedisTTLPolicy.FCM_TOKEN).isEqualTo(Duration.ofHours(12))
        assertThat(RedisTTLPolicy.REFRESH_TOKEN).isEqualTo(Duration.ofDays(7))
        assertThat(RedisTTLPolicy.JOIN_REQUEST).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `invite link TTLs match the documented policy`() {
        assertThat(RedisTTLPolicy.INVITE_LINK).isEqualTo(Duration.ofDays(7))
        assertThat(RedisTTLPolicy.INVITE_AUDIT).isEqualTo(Duration.ofDays(30))
    }
}
