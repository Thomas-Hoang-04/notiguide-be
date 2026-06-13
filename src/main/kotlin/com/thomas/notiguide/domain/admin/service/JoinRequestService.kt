package com.thomas.notiguide.domain.admin.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.domain.admin.dto.JoinRequestDto
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.types.AdminRole
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class JoinRequestService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val adminRepository: AdminRepository
) {
    private val secureRandom = SecureRandom()

    enum class TargetType { ORG, STORE }

    sealed interface Approval {
        data class AsAdmin(val storeId: UUID) : Approval
        data class AsSuperAdmin(val orgId: UUID) : Approval
    }

    data class JoinRequestPayload(
        val username: String = "",
        val passwordHash: String = "",
        val targetType: TargetType = TargetType.ORG,
        val targetId: String = "",
        val createdAt: String = ""
    )

    /** Creates a pending join request. Returns the opaque requestId. */
    suspend fun create(username: String, passwordHash: String, targetType: TargetType, targetId: UUID): String {
        val requestId = generateOpaqueToken()
        val usernameKey = RedisKeyManager.joinRequestUsername(username.lowercase())
        val reserved = redis.opsForValue().setIfAbsent(usernameKey, requestId, RedisTTLPolicy.JOIN_REQUEST).awaitSingle()
        if (!reserved) throw ConflictException("Username '$username' is already taken")

        val createdAt = OffsetDateTime.now()
        val payload = JoinRequestPayload(
            username = username,
            passwordHash = passwordHash,
            targetType = targetType,
            targetId = targetId.toString(),
            createdAt = createdAt.toString()
        )
        val ttl = RedisTTLPolicy.JOIN_REQUEST

        try {
            redis.opsForValue().set(RedisKeyManager.joinRequest(requestId), objectMapper.writeValueAsString(payload), ttl).awaitSingle()

            val indexKey = when (targetType) {
                TargetType.ORG -> RedisKeyManager.joinRequestOrgIndex(targetId)
                TargetType.STORE -> RedisKeyManager.joinRequestStoreIndex(targetId)
            }
            redis.opsForZSet().add(indexKey, requestId, createdAt.toInstant().toEpochMilli().toDouble()).awaitSingle()
        } catch (ex: Exception) {
            redis.delete(usernameKey).awaitSingleOrNull()
            redis.delete(RedisKeyManager.joinRequest(requestId)).awaitSingleOrNull()
            throw ex
        }
        return requestId
    }

    suspend fun usernameReserved(usernameLower: String): Boolean =
        redis.hasKey(RedisKeyManager.joinRequestUsername(usernameLower)).awaitSingle()

    /** For the login "pending" hint: resolve a pending request by username. */
    suspend fun findPendingByUsername(usernameLower: String): JoinRequestPayload? {
        val requestId = redis.opsForValue().get(RedisKeyManager.joinRequestUsername(usernameLower)).awaitSingleOrNull()
            ?: return null
        return get(requestId)
    }

    suspend fun get(requestId: String): JoinRequestPayload? {
        val json = redis.opsForValue().get(RedisKeyManager.joinRequest(requestId)).awaitSingleOrNull() ?: return null
        return runCatching { objectMapper.readValue(json, JoinRequestPayload::class.java) }.getOrNull()
    }

    suspend fun listByOrg(orgId: UUID): List<JoinRequestDto> =
        hydrate(RedisKeyManager.joinRequestOrgIndex(orgId))

    suspend fun listByStore(storeId: UUID): List<JoinRequestDto> =
        hydrate(RedisKeyManager.joinRequestStoreIndex(storeId))

    private suspend fun hydrate(indexKey: String): List<JoinRequestDto> {
        val ids = redis.opsForZSet().reverseRange(indexKey, Range.unbounded()).collectList().awaitSingle()
        val result = mutableListOf<JoinRequestDto>()
        for (id in ids) {
            val payload = get(id)
            if (payload == null) {
                redis.opsForZSet().remove(indexKey, id).awaitSingleOrNull() // lazy prune expired
                continue
            }
            result.add(JoinRequestDto(requestId = id, username = payload.username, createdAt = payload.createdAt))
        }
        return result
    }

    /** Materialize an admin row from a request per the approval decision, then delete the request. */
    @Transactional
    suspend fun approve(requestId: String, approval: Approval, verifierId: UUID): Admin {
        val lockKey = RedisKeyManager.joinRequestLock(requestId)
        val locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30)).awaitSingle()
        if (!locked) throw ConflictException("Join request is already being processed")
        val payload = get(requestId) ?: throw ConflictException("Join request not found or expired")
        try {
            if (adminRepository.existsByUsername(payload.username)) {
                // Per spec, keep the request so the owner can retry/reject or let it expire.
                throw ConflictException("Username '${payload.username}' is already taken")
            }
            val now = OffsetDateTime.now()
            val admin = adminRepository.save(
                when (approval) {
                    is Approval.AsAdmin -> Admin(
                        username = payload.username,
                        passwordHash = payload.passwordHash,
                        role = AdminRole.ROLE_ADMIN,
                        storeId = approval.storeId,
                        orgId = null,
                        isVerified = true,
                        verifiedBy = verifierId,
                        verifiedAt = now,
                    )
                    is Approval.AsSuperAdmin -> Admin(
                        username = payload.username,
                        passwordHash = payload.passwordHash,
                        role = AdminRole.ROLE_SUPER_ADMIN,
                        storeId = null,
                        orgId = approval.orgId,
                        isVerified = true,
                        verifiedBy = verifierId,
                        verifiedAt = now,
                    )
                },
            )
            deleteKeys(requestId, payload)
            return admin
        } finally {
            redis.delete(lockKey).awaitSingleOrNull()
        }
    }

    suspend fun reject(requestId: String) {
        val payload = get(requestId) ?: return
        deleteKeys(requestId, payload)
    }

    private suspend fun deleteKeys(requestId: String, payload: JoinRequestPayload) {
        redis.delete(RedisKeyManager.joinRequest(requestId)).awaitSingleOrNull()
        redis.delete(RedisKeyManager.joinRequestUsername(payload.username.lowercase())).awaitSingleOrNull()
        val indexKey = when (payload.targetType) {
            TargetType.ORG -> RedisKeyManager.joinRequestOrgIndex(UUID.fromString(payload.targetId))
            TargetType.STORE -> RedisKeyManager.joinRequestStoreIndex(UUID.fromString(payload.targetId))
        }
        redis.opsForZSet().remove(indexKey, requestId).awaitSingleOrNull()
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
