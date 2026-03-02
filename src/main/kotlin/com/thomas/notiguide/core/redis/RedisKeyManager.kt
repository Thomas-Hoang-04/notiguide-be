package com.thomas.notiguide.core.redis

import java.time.LocalDate
import java.util.UUID

object RedisKeyManager {
    fun queue(storeId: UUID) = "store:$storeId:queue"
    fun serving(storeId: UUID) = "store:$storeId:serving"
    fun ticket(storeId: UUID, ticketId: UUID) = "ticket:$storeId:$ticketId"
    fun counter(storeId: UUID, date: LocalDate = LocalDate.now()) = "store:$storeId:counter:$date"

    fun isTicketKey(key: String) = key.startsWith("ticket:")

    fun parseTicketKey(key: String): Pair<UUID, UUID>? {
        val parts = key.removePrefix("ticket:").split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            UUID.fromString(parts[0]) to UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
