package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.types.RegisterMode
import com.thomas.notiguide.domain.admin.request.RegisterRequest
import com.thomas.notiguide.domain.admin.types.RegisterStatus
import com.thomas.notiguide.domain.admin.response.RegisterResponse
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.organization.entity.Organization
import com.thomas.notiguide.domain.organization.repository.OrganizationRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.service.StoreService
import kotlinx.coroutines.CancellationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RegistrationService(
    private val adminRepository: AdminRepository,
    private val organizationRepository: OrganizationRepository,
    private val storeRepository: StoreRepository,
    private val storeService: StoreService,
    private val passwordEncoder: PasswordEncoder,
    private val joinRequestService: JoinRequestService,
    private val inviteLinkService: InviteLinkService
) {
    @Transactional
    suspend fun register(request: RegisterRequest): RegisterResponse {
        val username = request.username.trim()
        requireUsernameAvailable(username)
        val passwordHash = passwordEncoder.encode(request.password)

        return when (request.mode) {
            RegisterMode.CREATE_ORG -> createOrg(username, passwordHash, request)
            RegisterMode.CREATE_STORE -> createStore(username, passwordHash, request)
            RegisterMode.JOIN -> join(username, passwordHash, request)
        }
    }

    private suspend fun requireUsernameAvailable(username: String) {
        if (adminRepository.existsByUsername(username))
            throw ConflictException("Username '$username' is already taken")
        if (joinRequestService.usernameReserved(username.lowercase()))
            throw ConflictException("Username '$username' is already taken")
    }

    private suspend fun createOrg(username: String, passwordHash: String, request: RegisterRequest): RegisterResponse {
        val orgName = request.orgName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "Organization name is required")

        val org = organizationRepository.save(
            Organization(name = orgName)
        )
        val admin = adminRepository.save(
            Admin(
                username = username,
                passwordHash = passwordHash,
                role = AdminRole.ROLE_SUPER_ADMIN,
                orgId = org.id,
                storeId = null,
                isVerified = true
            )
        )
        organizationRepository.save(org.copy(createdBy = admin.id))
        return RegisterResponse(status = RegisterStatus.ACTIVE, role = AdminRole.ROLE_SUPER_ADMIN)
    }

    private suspend fun createStore(username: String, passwordHash: String, request: RegisterRequest): RegisterResponse {
        val storeName = request.storeName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "Store name is required")

        val store = storeService.createStore(
            CreateStoreRequest(name = storeName, address = request.storeAddress?.takeIf { it.isNotBlank() }),
            orgId = null
        )
        adminRepository.save(
            Admin(
                username = username,
                passwordHash = passwordHash,
                role = AdminRole.ROLE_ADMIN,
                orgId = null,
                storeId = store.id,
                isVerified = true
            )
        )
        return RegisterResponse(status = RegisterStatus.ACTIVE, role = AdminRole.ROLE_ADMIN)
    }

    private suspend fun join(username: String, passwordHash: String, request: RegisterRequest): RegisterResponse {
        val inviteToken = request.inviteToken?.trim()?.takeIf { it.isNotBlank() }
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "An invite link is required")
        return joinViaInviteToken(username, passwordHash, inviteToken)
    }

    private suspend fun joinViaInviteToken(username: String, passwordHash: String, token: String): RegisterResponse {
        val target = inviteLinkService.resolve(token)
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid or expired invite link")
        val targetId = runCatching { UUID.fromString(target.targetId) }.getOrNull()
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid or expired invite link")

        // findById(targetId) guarantees the entity id equals targetId, so the
        // non-null targetId is used for create/recordUse — no `!!` on entity ids.
        return when (target.targetType) {
            JoinRequestService.TargetType.ORG -> {
                organizationRepository.findById(targetId)
                    ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid or expired invite link")
                joinRequestService.create(username, passwordHash, JoinRequestService.TargetType.ORG, targetId)
                recordUseSafely(JoinRequestService.TargetType.ORG, targetId, username, token)
                RegisterResponse(RegisterStatus.PENDING, AdminRole.ROLE_ADMIN, "ORG")
            }
            JoinRequestService.TargetType.STORE -> {
                val store = storeRepository.findById(targetId)
                    ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid or expired invite link")
                if (store.orgId != null)
                    throw HttpException(HttpStatus.BAD_REQUEST, "Invalid or expired invite link")
                joinRequestService.create(username, passwordHash, JoinRequestService.TargetType.STORE, targetId)
                recordUseSafely(JoinRequestService.TargetType.STORE, targetId, username, token)
                RegisterResponse(RegisterStatus.PENDING, AdminRole.ROLE_ADMIN, "STORE")
            }
        }
    }

    /** Best-effort: the usage trail must never fail a registration (defense in depth on top of recordUse's own guarantee). */
    private suspend fun recordUseSafely(
        targetType: JoinRequestService.TargetType,
        targetId: UUID,
        username: String,
        token: String
    ) {
        try {
            inviteLinkService.recordUse(targetType, targetId, username, token)
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            // logged inside recordUse's own guard when it is the source; swallowed here by design
        }
    }
}
