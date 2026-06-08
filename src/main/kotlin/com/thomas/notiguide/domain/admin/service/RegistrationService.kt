package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.tenant.JoinCodeGenerator
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
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RegistrationService(
    private val adminRepository: AdminRepository,
    private val organizationRepository: OrganizationRepository,
    private val storeRepository: StoreRepository,
    private val storeService: StoreService,
    private val passwordEncoder: PasswordEncoder,
    private val joinRequestService: JoinRequestService
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
            Organization(name = orgName, joinCode = generateUniqueOrgCode())
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
        val code = request.joinCode?.trim()?.takeIf { it.isNotBlank() }
            ?: throw HttpException(HttpStatus.BAD_REQUEST, "Join code is required")

        when {
            code.startsWith(JoinCodeGenerator.ORG_PREFIX) -> {
                val org = organizationRepository.findByJoinCode(code)
                    ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid join code")
                joinRequestService.create(username, passwordHash, JoinRequestService.TargetType.ORG, org.id!!)
                return RegisterResponse(RegisterStatus.PENDING, AdminRole.ROLE_ADMIN, "ORG")
            }
            code.startsWith(JoinCodeGenerator.STORE_PREFIX) -> {
                val store = storeRepository.findByJoinCode(code)
                    ?: throw HttpException(HttpStatus.BAD_REQUEST, "Invalid join code")
                if (store.orgId != null)
                    throw HttpException(HttpStatus.BAD_REQUEST, "Invalid join code")
                joinRequestService.create(username, passwordHash, JoinRequestService.TargetType.STORE, store.id!!)
                return RegisterResponse(RegisterStatus.PENDING, AdminRole.ROLE_ADMIN, "STORE")
            }
            else -> throw HttpException(HttpStatus.BAD_REQUEST, "Invalid join code")
        }
    }

    private suspend fun generateUniqueOrgCode(): String {
        repeat(5) {
            val code = JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX)
            if (!organizationRepository.existsByJoinCode(code)) return code
        }
        throw IllegalStateException("Could not generate a unique organization join code")
    }
}
