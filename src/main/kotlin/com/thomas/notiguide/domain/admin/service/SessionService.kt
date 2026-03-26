package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.dto.AdminSessionDto
import com.thomas.notiguide.domain.admin.entity.AdminSession
import com.thomas.notiguide.domain.admin.repository.AdminSessionRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SessionService(
    private val sessionRepository: AdminSessionRepository,
    private val redis: ReactiveRedisTemplate<String, String>,
    private val jwtProperties: JWTProperties
) {

    suspend fun createSession(adminId: UUID, tokenHash: String, ip: String, userAgent: String?): AdminSession {
        return sessionRepository.save(
            AdminSession(adminId = adminId, tokenHash = tokenHash, ipAddress = ip, userAgent = userAgent)
        )
    }

    suspend fun listSessions(adminId: UUID, currentTokenHash: String): List<AdminSessionDto> {
        return sessionRepository.findByAdminIdOrderByLastActiveDesc(adminId)
            .toList()
            .map { it.toDto(isCurrent = it.tokenHash == currentTokenHash) }
    }

    suspend fun revokeSession(sessionId: UUID, adminId: UUID, currentTokenHash: String) {
        val session = sessionRepository.findById(sessionId)
            ?: throw NotFoundException("Session", "id", sessionId.toString())
        if (session.adminId != adminId) throw ForbiddenException("Cannot revoke another admin's session")
        if (session.tokenHash == currentTokenHash) throw ConflictException("Cannot revoke current session")

        val ttl = Duration.ofSeconds(jwtProperties.accessExpirySeconds)
        redis.opsForValue()
            .set(RedisKeyManager.revokedToken(session.tokenHash), "1", ttl)
            .awaitSingle()

        sessionRepository.deleteById(sessionId)
    }

    suspend fun updateLastActive(tokenHash: String) {
        val throttleKey = RedisKeyManager.sessionLastUpdate(tokenHash)
        val set = redis.opsForValue()
            .setIfAbsent(throttleKey, "1", Duration.ofMinutes(5))
            .awaitSingle()
        if (set) {
            val session = sessionRepository.findByTokenHash(tokenHash) ?: return
            sessionRepository.save(session.copy(lastActive = OffsetDateTime.now()))
        }
    }

    suspend fun isRevoked(tokenHash: String): Boolean {
        return redis.hasKey(RedisKeyManager.revokedToken(tokenHash)).awaitSingle()
    }

    suspend fun deleteAllForAdmin(adminId: UUID) {
        sessionRepository.deleteByAdminId(adminId)
    }

    suspend fun deleteByTokenHash(tokenHash: String) {
        sessionRepository.deleteByTokenHash(tokenHash)
    }

    private fun AdminSession.toDto(isCurrent: Boolean) = AdminSessionDto(
        id = id.toString(),
        ipAddress = ipAddress,
        userAgent = userAgent,
        lastActive = lastActive,
        createdAt = createdAt,
        isCurrent = isCurrent
    )
}
