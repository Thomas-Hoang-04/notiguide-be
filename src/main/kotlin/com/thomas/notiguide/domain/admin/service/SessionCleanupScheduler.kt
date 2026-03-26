package com.thomas.notiguide.domain.admin.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SessionCleanupScheduler(
    private val client: DatabaseClient
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "0 30 3 * * *")
    fun cleanupExpiredSessions() = runBlocking {
        val deleted = client.sql("DELETE FROM admin_session WHERE created_at < now() - INTERVAL '8 days'")
            .fetch().rowsUpdated().awaitSingle()
        if (deleted > 0) {
            log.info("Cleaned up {} expired sessions", deleted)
        }
    }
}
