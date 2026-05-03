package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.jwt.TokenHashUtil
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.dto.EnrollmentTokenIssueResponse
import com.thomas.notiguide.domain.device.dto.EnrollmentTokenMetadataDto
import com.thomas.notiguide.domain.device.request.IssueEnrollmentTokenRequest
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Service
class EnrollmentTokenService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val storeRepository: StoreRepository,
    private val objectMapper: ObjectMapper,
    private val properties: DeviceCommandSigningProperties
) {

    private val secureRandom = SecureRandom()

    suspend fun issue(
        request: IssueEnrollmentTokenRequest,
        principal: AdminPrincipal
    ): EnrollmentTokenIssueResponse {
        val effectiveStoreId = resolveStoreIdForIssue(request.storeId, principal)
        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val expiresAt = issuedAt.plusSeconds(properties.enrollment.tokenTtlSeconds)
        val ttl = Duration.ofSeconds(properties.enrollment.tokenTtlSeconds)

        repeat(8) {
            val token = generateToken()
            val tokenHash = TokenHashUtil.sha256(token.trim())
            val key = RedisKeyManager.enrollmentToken(tokenHash)
            val payload = EnrollmentTokenRecord(
                storeId = effectiveStoreId,
                issuedByAdminId = principal.id,
                issuedAt = issuedAt,
                expiresAt = expiresAt
            )

            val created = redis.opsForValue()
                .setIfAbsent(key, objectMapper.writeValueAsString(payload), ttl)
                .awaitSingle()

            if (created) {
                return EnrollmentTokenIssueResponse(
                    token = token,
                    tokenHash = tokenHash,
                    expiresAt = expiresAt
                )
            }
        }

        throw IllegalStateException("Unable to generate a unique enrollment token")
    }

    suspend fun list(principal: AdminPrincipal): List<EnrollmentTokenMetadataDto> {
        val visibleStoreId = if (isSuperAdmin(principal)) {
            null
        } else {
            principal.storeId ?: throw ForbiddenException("Store-scoped admins need an assigned store to view enrollment tokens")
        }

        val keys = redis.scan(
            ScanOptions.scanOptions()
                .match(RedisKeyManager.enrollmentTokenPattern())
                .count(100)
                .build()
        ).collectList().awaitSingle()

        val entries = mutableListOf<EnrollmentTokenMetadataDto>()
        for (key in keys) {
            val payloadJson = redis.opsForValue().get(key).awaitSingleOrNull() ?: continue
            val record = runCatching { objectMapper.readValue(payloadJson, EnrollmentTokenRecord::class.java) }
                .getOrNull()
                ?: continue

            if (visibleStoreId != null && record.storeId != visibleStoreId) {
                continue
            }

            entries += EnrollmentTokenMetadataDto(
                tokenHash = key.removePrefix("enroll:"),
                storeId = record.storeId,
                issuedAt = record.issuedAt,
                expiresAt = record.expiresAt
            )
        }

        return entries.sortedByDescending { it.issuedAt }
    }

    suspend fun revoke(
        tokenHash: String,
        principal: AdminPrincipal
    ) {
        val normalizedHash = tokenHash.trim().lowercase()
        val key = RedisKeyManager.enrollmentToken(normalizedHash)
        val payloadJson = redis.opsForValue().get(key).awaitSingleOrNull() ?: return
        val record = runCatching { objectMapper.readValue(payloadJson, EnrollmentTokenRecord::class.java) }
            .getOrNull()
            ?: return

        if (!isSuperAdmin(principal)) {
            val principalStoreId = principal.storeId
                ?: throw ForbiddenException("Store-scoped admins need an assigned store to revoke enrollment tokens")
            if (record.storeId != principalStoreId) {
                throw ForbiddenException("You do not have access to this store")
            }
        }

        redis.delete(key).awaitSingleOrNull()
    }

    suspend fun consume(token: String): EnrollmentTokenRecord? {
        val normalized = token.trim()
        if (normalized.isEmpty()) return null
        val key = RedisKeyManager.enrollmentToken(TokenHashUtil.sha256(normalized))
        val payloadJson = redis.opsForValue().getAndDelete(key).awaitSingleOrNull() ?: return null
        return runCatching { objectMapper.readValue(payloadJson, EnrollmentTokenRecord::class.java) }.getOrNull()
    }

    private suspend fun resolveStoreIdForIssue(
        requestedStoreId: UUID?,
        principal: AdminPrincipal
    ): UUID? {
        val storeId = if (isSuperAdmin(principal)) {
            requestedStoreId
        } else {
            val principalStoreId = principal.storeId
                ?: throw ForbiddenException("Store-scoped admins need an assigned store to issue enrollment tokens")
            if (requestedStoreId != null && requestedStoreId != principalStoreId) {
                throw ForbiddenException("You do not have access to this store")
            }
            principalStoreId
        }

        if (storeId != null && storeRepository.findById(storeId) == null) {
            throw NotFoundException("Store", "id", storeId.toString())
        }

        return storeId
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    data class EnrollmentTokenRecord(
        val storeId: UUID? = null,
        val issuedByAdminId: UUID = UUID(0L, 0L),
        val issuedAt: OffsetDateTime = OffsetDateTime.MIN,
        val expiresAt: OffsetDateTime = OffsetDateTime.MIN
    )
}
