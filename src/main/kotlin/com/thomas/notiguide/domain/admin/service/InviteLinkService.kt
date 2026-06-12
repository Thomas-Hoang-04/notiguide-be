package com.thomas.notiguide.domain.admin.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import com.thomas.notiguide.core.tenant.InviteTokenGenerator
import com.thomas.notiguide.domain.admin.response.InviteResolveResponse
import com.thomas.notiguide.domain.organization.repository.OrganizationRepository
import com.thomas.notiguide.domain.organization.response.InviteLinkResponse
import com.thomas.notiguide.domain.organization.dto.InviteLinkUse
import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class InviteLinkService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val organizationRepository: OrganizationRepository,
    private val storeRepository: StoreRepository
) {
    private val log = LoggerFactory.getLogger(InviteLinkService::class.java)

    /** Token payload stored at invite:token:{token}. */
    data class InviteTarget(
        val targetType: JoinRequestService.TargetType = JoinRequestService.TargetType.ORG,
        val targetId: String = "",
        val expiresAt: String = ""
    )

    /** Active-link payload stored at invite:active:{type}:{id}. */
    data class ActiveLink(
        val token: String = "",
        val expiresAt: String = ""
    )

    companion object {
        private const val TOKEN_BYTE_COUNT = 16
        private const val MAX_TOKEN_LENGTH = 64
        private const val MAX_AUDIT_ENTRIES = 200L
        private const val RECENT_USES_LIMIT = 20L
        private val LOCK_TTL: Duration = Duration.ofSeconds(10)
    }

    /** Current link state, or null when no active link exists. Never mints. */
    suspend fun getActive(targetType: JoinRequestService.TargetType, targetId: UUID): InviteLinkResponse? {
        val json = redis.opsForValue()
            .get(RedisKeyManager.inviteActive(targetType.name, targetId))
            .awaitSingleOrNull() ?: return null
        val active = runCatching { objectMapper.readValue(json, ActiveLink::class.java) }.getOrNull()
            ?: return null
        if (active.token.isBlank()) return null
        return InviteLinkResponse(token = active.token, expiresAt = active.expiresAt)
    }

    /**
     * Mints a new link, revoking any previous one. Serialized per tenant by the
     * invite:lock mutex. The old token key is deleted BEFORE the new pair is
     * written so no partial failure can leave a revoked token resolvable.
     */
    suspend fun regenerate(targetType: JoinRequestService.TargetType, targetId: UUID): InviteLinkResponse {
        val lockKey = RedisKeyManager.inviteLock(targetType.name, targetId)
        val locked = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL).awaitSingle()
        if (!locked) throw ConflictException("Invite link is already being regenerated")
        try {
            val activeKey = RedisKeyManager.inviteActive(targetType.name, targetId)
            val oldToken = redis.opsForValue().get(activeKey).awaitSingleOrNull()
                ?.let { runCatching { objectMapper.readValue(it, ActiveLink::class.java) }.getOrNull() }
                ?.token
                ?.takeIf { it.isNotBlank() }
            if (oldToken != null) {
                redis.delete(RedisKeyManager.inviteToken(oldToken)).awaitSingleOrNull()
            }

            val token = InviteTokenGenerator.generate(TOKEN_BYTE_COUNT)
            val expiresAt = OffsetDateTime.now().plus(RedisTTLPolicy.INVITE_LINK).toString()
            val payload = InviteTarget(
                targetType = targetType,
                targetId = targetId.toString(),
                expiresAt = expiresAt
            )
            redis.opsForValue()
                .set(
                    RedisKeyManager.inviteToken(token),
                    objectMapper.writeValueAsString(payload),
                    RedisTTLPolicy.INVITE_LINK
                )
                .awaitSingle()
            redis.opsForValue()
                .set(
                    activeKey,
                    objectMapper.writeValueAsString(ActiveLink(token = token, expiresAt = expiresAt)),
                    RedisTTLPolicy.INVITE_LINK
                )
                .awaitSingle()
            return InviteLinkResponse(token = token, expiresAt = expiresAt)
        } finally {
            // NonCancellable: a cancelled request must still release the mutex,
            // otherwise the tenant is locked out of regenerating for the lock TTL.
            withContext(NonCancellable) {
                redis.delete(lockKey).awaitSingleOrNull()
            }
        }
    }

    /** Read-only token resolution — never consumes. Null when unknown/expired. */
    suspend fun resolve(token: String): InviteTarget? {
        if (!token.startsWith(InviteTokenGenerator.PREFIX) || token.length > MAX_TOKEN_LENGTH) return null
        val json = redis.opsForValue().get(RedisKeyManager.inviteToken(token)).awaitSingleOrNull()
            ?: return null
        return runCatching { objectMapper.readValue(json, InviteTarget::class.java) }.getOrNull()
    }

    /** resolve() + display-name lookup. Null on ANY failure — backs the public endpoint's 404. */
    suspend fun resolveForDisplay(token: String): InviteResolveResponse? {
        val target = resolve(token) ?: return null
        val targetId = runCatching { UUID.fromString(target.targetId) }.getOrNull() ?: return null
        return when (target.targetType) {
            JoinRequestService.TargetType.ORG ->
                organizationRepository.findById(targetId)
                    ?.let { InviteResolveResponse(targetType = "ORG", name = it.name) }
            JoinRequestService.TargetType.STORE ->
                storeRepository.findById(targetId)
                    ?.takeIf { it.orgId == null }
                    ?.let { InviteResolveResponse(targetType = "STORE", name = it.name) }
        }
    }

    /** Best-effort usage-trail append — catches and logs every failure, never throws. */
    suspend fun recordUse(
        targetType: JoinRequestService.TargetType,
        targetId: UUID,
        username: String,
        token: String
    ) {
        try {
            val key = RedisKeyManager.inviteAudit(targetType.name, targetId)
            val now = OffsetDateTime.now()
            val entry = InviteLinkUse(
                username = username,
                usedAt = now.toString(),
                linkId = token.takeLast(4)
            )
            redis.opsForZSet()
                .add(key, objectMapper.writeValueAsString(entry), now.toInstant().toEpochMilli().toDouble())
                .awaitSingle()
            val cutoff = now.minus(RedisTTLPolicy.INVITE_AUDIT).toInstant().toEpochMilli().toDouble()
            redis.opsForZSet().removeRangeByScore(key, Range.closed(0.0, cutoff)).awaitSingle()
            redis.opsForZSet().removeRange(key, Range.closed(0L, -(MAX_AUDIT_ENTRIES + 1))).awaitSingle()
            redis.expire(key, RedisTTLPolicy.INVITE_AUDIT).awaitSingle()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("Failed to record invite link use for {} {}: {}", targetType, targetId, ex.message)
        }
    }

    /** Newest ≤ 20 trail entries; lazily prunes >30-day entries; skips malformed members. */
    suspend fun getRecentUses(targetType: JoinRequestService.TargetType, targetId: UUID): List<InviteLinkUse> {
        val key = RedisKeyManager.inviteAudit(targetType.name, targetId)
        val cutoff = OffsetDateTime.now().minus(RedisTTLPolicy.INVITE_AUDIT).toInstant().toEpochMilli().toDouble()
        redis.opsForZSet().removeRangeByScore(key, Range.closed(0.0, cutoff)).awaitSingleOrNull()
        val members = redis.opsForZSet()
            .reverseRange(key, Range.closed(0L, RECENT_USES_LIMIT - 1))
            .collectList()
            .awaitSingle()
        return members.mapNotNull { member ->
            runCatching { objectMapper.readValue(member, InviteLinkUse::class.java) }.getOrNull()
        }
    }
}
