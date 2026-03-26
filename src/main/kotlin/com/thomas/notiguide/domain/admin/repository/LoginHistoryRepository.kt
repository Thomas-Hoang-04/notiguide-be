package com.thomas.notiguide.domain.admin.repository

import com.thomas.notiguide.domain.admin.entity.LoginHistory
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

interface LoginHistoryRepository : CoroutineCrudRepository<LoginHistory, UUID>

@Repository
class LoginHistoryQueryRepository(
    private val client: DatabaseClient
) {
    suspend fun findRecentByAdminId(adminId: UUID, limit: Int): List<LoginHistory> {
        return client.sql(
            """
            SELECT id, admin_id, ip_address, success, created_at
            FROM login_history
            WHERE admin_id = :adminId
            ORDER BY created_at DESC
            LIMIT :limit
            """.trimIndent()
        )
            .bind("adminId", adminId)
            .bind("limit", limit + 1)
            .map { row: Row, _ ->
                LoginHistory(
                    id = row.get("id", UUID::class.java),
                    adminId = row.get("admin_id", UUID::class.java)!!,
                    ipAddress = row.get("ip_address", String::class.java)!!,
                    success = row.get("success", Boolean::class.java)!!,
                    createdAt = row.get("created_at", OffsetDateTime::class.java)
                )
            }
            .all()
            .asFlow()
            .toList()
    }
}
