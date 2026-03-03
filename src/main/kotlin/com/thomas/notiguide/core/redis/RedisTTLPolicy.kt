package com.thomas.notiguide.core.redis

import java.time.Duration

object RedisTTLPolicy {
    val TICKET_WAITING: Duration = Duration.ofHours(12)
    val TICKET_CALLED: Duration = Duration.ofMinutes(30)
    val DAILY_COUNTER: Duration = Duration.ofHours(24)
}
