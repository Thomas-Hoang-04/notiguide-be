package com.thomas.notiguide.core.jwt

import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.UUID

@Service
class RefreshTokenService(
    private val redis: ReactiveRedisTemplate<String, String>
) {

    private val secureRandom = SecureRandom()

    /**
     * Issues a new opaque refresh token for the given admin.
     * Stores: refresh:{token} -> adminId (with TTL)
     * Tracks: admin:{adminId}:refresh_tokens set (for bulk revocation)
     */
    suspend fun issue(adminId: UUID): String {
        val token = generateOpaqueToken()
        val key = RedisKeyManager.refreshToken(token)
        val trackingKey = RedisKeyManager.adminRefreshTokens(adminId)
        val ttl = RedisTTLPolicy.REFRESH_TOKEN

        redis.opsForValue().set(key, adminId.toString(), ttl).awaitSingle()
        redis.opsForSet().add(trackingKey, token).awaitSingle()
        redis.expire(trackingKey, ttl).awaitSingle()

        return token
    }

    /**
     * Validates and rotates a refresh token. Returns (adminId, newToken) or null if invalid.
     * One-time use: atomic DELETE acts as the mutex — only one caller succeeds.
     */
    suspend fun rotate(token: String): Pair<UUID, String>? {
        val key = RedisKeyManager.refreshToken(token)
        val adminIdStr = redis.opsForValue().get(key).awaitSingleOrNull() ?: return null
        val adminId = UUID.fromString(adminIdStr)

        // Atomic delete — if another request already consumed the token, delete returns 0
        val deleted = redis.delete(key).awaitSingle()
        if (deleted == 0L) return null

        // Remove from tracking set
        val trackingKey = RedisKeyManager.adminRefreshTokens(adminId)
        redis.opsForSet().remove(trackingKey, token).awaitSingleOrNull()

        // Issue a new token
        val newToken = issue(adminId)
        return adminId to newToken
    }

    /**
     * Revokes a single refresh token (e.g., on logout).
     */
    suspend fun revoke(token: String) {
        val key = RedisKeyManager.refreshToken(token)
        val adminIdStr = redis.opsForValue().get(key).awaitSingleOrNull() ?: return
        val adminId = UUID.fromString(adminIdStr)

        redis.delete(key).awaitSingle()
        val trackingKey = RedisKeyManager.adminRefreshTokens(adminId)
        redis.opsForSet().remove(trackingKey, token).awaitSingleOrNull()
    }

    /**
     * Revokes all refresh tokens for an admin (e.g., on password change or account deletion).
     */
    suspend fun revokeAll(adminId: UUID) {
        val trackingKey = RedisKeyManager.adminRefreshTokens(adminId)
        val tokens = redis.opsForSet().members(trackingKey)
            .collectList().awaitSingle()

        if (tokens.isNotEmpty()) {
            val keys = tokens.map { RedisKeyManager.refreshToken(it) } + trackingKey
            redis.delete(*keys.toTypedArray()).awaitSingle()
        }
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
