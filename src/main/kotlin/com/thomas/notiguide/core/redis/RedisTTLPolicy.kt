package com.thomas.notiguide.core.redis

import java.time.Duration

object RedisTTLPolicy {
    val TICKET_WAITING: Duration = Duration.ofHours(12)
    val TICKET_CALLED: Duration = Duration.ofMinutes(30)
    val TICKET_TERMINAL: Duration = Duration.ofHours(2)
    val FCM_TOKEN: Duration = Duration.ofHours(12)
    val REFRESH_TOKEN: Duration = Duration.ofDays(7)
    val JOIN_REQUEST: Duration = Duration.ofDays(7)
    val INVITE_LINK: Duration = Duration.ofDays(7)
    val INVITE_AUDIT: Duration = Duration.ofDays(30)
}
