package com.thomas.notiguide.core.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.repository.LoginHistoryRepository
import com.thomas.notiguide.domain.admin.service.SessionService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID

/**
 * Lets a client roll back the server-side artifacts of a login response that
 * never reached the browser as a usable session (e.g. cross-site cookies blocked).
 *
 * Issue: at the end of `AuthController.login`, after the session row, refresh
 * token, and login_history success entry have been created, we mint a one-shot
 * opaque token bound to those identifiers (Redis, ~60s TTL) and return it in
 * the response body. The client decides whether to consume it based on a
 * post-login session-verification ping — if cookies didn't stick, it POSTs the
 * token back and we revoke + delete the artifacts. Cleanup is retryable for
 * the token's short TTL: we only consume the token after every rollback step
 * succeeds, so a transient Redis/DB failure can be retried safely.
 *
 * The token is only knowable to the same client that received the login
 * response and self-expires fast, so it doubles as a CSRF-resistant capability
 * for this otherwise public endpoint.
 */
@Service
class LoginAbortService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val refreshTokenService: RefreshTokenService,
    private val sessionService: SessionService,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val secureRandom = SecureRandom()

    suspend fun issue(
        accessTokenHash: String,
        refreshToken: String,
        loginHistoryId: UUID
    ): String {
        val token = generateOpaqueToken()
        val payload = AbortPayload(
            accessTokenHash = accessTokenHash,
            refreshToken = refreshToken,
            loginHistoryId = loginHistoryId.toString()
        )
        redis.opsForValue()
            .set(
                RedisKeyManager.loginAbortToken(token),
                objectMapper.writeValueAsString(payload),
                Duration.ofSeconds(ABORT_TOKEN_TTL_SECONDS)
            )
            .awaitSingle()
        return token
    }

    /** Returns true if the abort token was valid and the artifacts were rolled back. */
    suspend fun consume(abortToken: String): Boolean {
        try {
            return consumeInternal(abortToken)
        } catch (ex: Exception) {
            log.warn("Login abort consume failed", ex)
            return false
        }
    }

    private suspend fun consumeInternal(abortToken: String): Boolean {
        val key = RedisKeyManager.loginAbortToken(abortToken)
        val lockKey = RedisKeyManager.loginAbortLock(abortToken)
        val payloadJson = redis.opsForValue().get(key).awaitSingleOrNull() ?: return false
        // Short-lived mutex so concurrent callers can't race the same cleanup
        // while still allowing a later retry if this request partially fails.
        val locked = redis.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(ABORT_LOCK_TTL_SECONDS))
            .awaitSingle()
        if (!locked) return false

        try {
            val payload = runCatching { objectMapper.readValue(payloadJson, AbortPayload::class.java) }
                .getOrElse { ex ->
                    log.warn("Discarding malformed login-abort payload", ex)
                    redis.delete(key).awaitSingleOrNull()
                    return false
                }
            val loginHistoryId = runCatching { UUID.fromString(payload.loginHistoryId) }
                .getOrElse { ex ->
                    log.warn("Discarding login-abort token with invalid loginHistoryId", ex)
                    redis.delete(key).awaitSingleOrNull()
                    return false
                }

            val cleaned = rollbackArtifacts(
                accessTokenHash = payload.accessTokenHash,
                refreshToken = payload.refreshToken,
                loginHistoryId = loginHistoryId
            )
            if (!cleaned) return false

            redis.delete(key).awaitSingle()
            return true
        } finally {
            redis.delete(lockKey).awaitSingleOrNull()
        }
    }

    suspend fun rollbackArtifacts(
        accessTokenHash: String,
        refreshToken: String,
        loginHistoryId: UUID
    ): Boolean {
        var success = true

        runCatching { refreshTokenService.revoke(refreshToken) }
            .onFailure { ex ->
                success = false
                log.warn("Login abort cleanup failed while revoking refresh token", ex)
            }

        runCatching { sessionService.deleteByTokenHash(accessTokenHash) }
            .onFailure { ex ->
                success = false
                log.warn("Login abort cleanup failed while deleting session", ex)
            }

        runCatching { loginHistoryRepository.deleteById(loginHistoryId) }
            .onFailure { ex ->
                success = false
                log.warn("Login abort cleanup failed while deleting login history {}", loginHistoryId, ex)
            }

        return success
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class AbortPayload(
        val accessTokenHash: String = "",
        val refreshToken: String = "",
        val loginHistoryId: String = ""
    )

    companion object {
        private const val ABORT_TOKEN_TTL_SECONDS = 60L
        private const val ABORT_LOCK_TTL_SECONDS = 10L
    }
}
