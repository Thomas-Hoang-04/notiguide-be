package com.thomas.notiguide.core.redis

import java.time.LocalDate
import java.util.UUID

object RedisKeyManager {
    fun queue(storeId: UUID) = "store:$storeId:queue"
    fun serving(storeId: UUID) = "store:$storeId:serving"
    fun ticket(storeId: UUID, ticketId: UUID) = "ticket:$storeId:$ticketId"
    fun ticketKeyPrefix(storeId: UUID) = "ticket:$storeId:"
    fun counter(storeId: UUID, date: LocalDate) = "store:$storeId:counter:$date"
    fun counterPattern(storeId: UUID) = "store:$storeId:counter:*"
    fun ticketPattern(storeId: UUID) = "ticket:$storeId:*"
    fun avgServiceDuration(storeId: UUID) = "store:$storeId:avg_service_seconds"
    fun fcmToken(storeId: UUID, ticketId: UUID) = "fcm:$storeId:$ticketId"
    fun fcmTokenPattern(storeId: UUID) = "fcm:$storeId:*"
    fun refreshToken(token: String) = "refresh:$token"
    fun adminRefreshTokens(adminId: UUID) = "admin:$adminId:refresh_tokens"
    fun loginAbortToken(token: String) = "auth:abort:$token"
    fun loginAbortLock(token: String) = "auth:abort:lock:$token"

    fun queueState(storeId: UUID) = "store:$storeId:queue_state"
    fun storeSettings(storeId: UUID) = "store:$storeId:settings"

    fun graceExpiry(storeId: UUID, ticketId: UUID) = "grace:$storeId:$ticketId"

    fun revokedToken(tokenHash: String) = "revoked:$tokenHash"
    fun sessionLastUpdate(tokenHash: String) = "session:$tokenHash:last_update"

    fun queue(storeId: UUID, serviceTypeId: UUID) = "store:$storeId:queue:$serviceTypeId"
    @Suppress("unused")
    fun serving(storeId: UUID, serviceTypeId: UUID) = "store:$storeId:serving:$serviceTypeId"
    @Suppress("unused")
    fun counterLanes(storeId: UUID, counterId: String) = "counter:$storeId:$counterId:lanes"

    fun isTicketKey(key: String) = key.startsWith("ticket:")
    fun isGraceExpiryKey(key: String) = key.startsWith("grace:")

    fun parseGraceExpiryKey(key: String): Pair<UUID, UUID>? {
        val parts = key.removePrefix("grace:").split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            UUID.fromString(parts[0]) to UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

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
