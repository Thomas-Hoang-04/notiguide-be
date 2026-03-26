package com.thomas.notiguide.core.redis

import java.time.Duration

object RedisTTLPolicy {
    val TICKET_WAITING: Duration = Duration.ofHours(12)
    val TICKET_CALLED: Duration = Duration.ofMinutes(30)
    val FCM_TOKEN: Duration = Duration.ofHours(12)
    val REFRESH_TOKEN: Duration = Duration.ofDays(7)
}
