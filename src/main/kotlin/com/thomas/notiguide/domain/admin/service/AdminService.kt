package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.jwt.RefreshTokenService
import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.response.AdminPageResponse
import com.thomas.notiguide.domain.admin.dto.LoginHistoryDto
import com.thomas.notiguide.domain.admin.response.LoginHistoryPageResponse
import com.thomas.notiguide.domain.admin.entity.LoginHistory
import com.thomas.notiguide.domain.admin.repository.LoginHistoryRepository
import com.thomas.notiguide.domain.admin.repository.LoginHistoryQueryRepository
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.CreateAdminRequest
import com.thomas.notiguide.domain.admin.request.UpdatePasswordRequest
import com.thomas.notiguide.domain.admin.request.UpdateUsernameRequest
import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.flow.toList
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AdminService(
    private val adminRepository: AdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val storeRepository: StoreRepository,
    private val refreshTokenService: RefreshTokenService,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val loginHistoryQueryRepository: LoginHistoryQueryRepository
) {

    @Transactional(readOnly = true)
    suspend fun getAdmin(id: UUID): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())
        return admin.toDto(resolveStoreName(admin.storeId))
    }

    private suspend fun resolveStoreName(storeId: UUID?): String? {
        return storeId?.let { storeRepository.findById(it)?.name }
    }

    private suspend fun resolveStoreNames(admins: List<Admin>): Map<UUID, String> {
        val storeIds = admins.mapNotNull { it.storeId }.distinct()
        if (storeIds.isEmpty()) return emptyMap()
        return storeRepository.findAllById(storeIds).toList().associate { it.id!! to it.name }
    }

    private suspend fun belongsToOrg(target: Admin, orgId: UUID): Boolean {
        if (target.orgId == orgId) return true
        val storeOrgId = target.storeId?.let { storeRepository.findById(it)?.orgId }
        if (storeOrgId == orgId) return true
        if (target.role == AdminRole.ROLE_ADMIN && target.storeId == null) {
            val creatorOrgId = target.createdBy?.let { adminRepository.findById(it)?.orgId }
            if (creatorOrgId == orgId) return true
        }
        return false
    }

    private suspend fun requireSameOrg(target: Admin, orgId: UUID) {
        if (!belongsToOrg(target, orgId)) throw ForbiddenException("Admin is not in your organization")
    }

    private suspend fun requireSameIndependentStore(target: Admin, storeId: UUID) {
        if (target.storeId != storeId) throw ForbiddenException("Admin is not in your store")
        val store = storeRepository.findById(storeId)
            ?: throw NotFoundException("Store", "id", storeId.toString())
        if (store.orgId != null)
            throw ForbiddenException("Store admins cannot verify organization-owned store members")
    }

    sealed interface VerifyScope {
        data class Org(val orgId: UUID) : VerifyScope
        data class IndependentStore(val storeId: UUID) : VerifyScope
    }

    @Transactional
    suspend fun createAdmin(request: CreateAdminRequest, createdById: UUID, actorOrgId: UUID): AdminDto {
        require(request.username.isNotBlank()) { "Username must not be blank" }
        require(request.password.isNotBlank()) { "Password must not be blank" }

        val username = request.username.trim()

        if (request.role == AdminRole.ROLE_SUPER_ADMIN)
            throw HttpException(HttpStatus.BAD_REQUEST, "Organization owners are created via registration, not here")

        if (adminRepository.existsByUsername(username))
            throw ConflictException("Username '$username' is already taken")

        val storeName = if (request.storeId != null) {
            val store = storeRepository.findById(request.storeId)
                ?: throw NotFoundException("Store", "id", request.storeId.toString())
            if (store.orgId != actorOrgId)
                throw ForbiddenException("Store is not in your organization")
            store.name
        } else null

        val admin = Admin(
            username = username,
            passwordHash = passwordEncoder.encode(request.password),
            role = request.role,
            storeId = request.storeId,
            isVerified = false,
            createdBy = createdById
        )
        return adminRepository.save(admin).toDto(storeName)
    }

    @Transactional
    suspend fun verifyAdmin(adminId: UUID, verifierId: UUID, scope: VerifyScope): AdminDto {
        if (adminId == verifierId)
            throw ForbiddenException("Cannot verify your own account")

        val admin = adminRepository.findById(adminId)
            ?: throw NotFoundException("Admin", "id", adminId.toString())

        if (admin.isVerified)
            throw ConflictException("Admin '${admin.username}' is already verified")

        when (scope) {
            is VerifyScope.Org -> requireSameOrg(admin, scope.orgId)
            is VerifyScope.IndependentStore -> {
                requireSameIndependentStore(admin, scope.storeId)
                if (admin.role == AdminRole.ROLE_SUPER_ADMIN)
                    throw ForbiddenException("Store admins cannot verify organization owners")
            }
        }

        val verified = admin.copy(
            isVerified = true,
            verifiedBy = verifierId,
            verifiedAt = OffsetDateTime.now()
        )
        return adminRepository.save(verified).toDto(resolveStoreName(verified.storeId))
    }

    @Transactional
    suspend fun updatePassword(id: UUID, request: UpdatePasswordRequest): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())

        if (!passwordEncoder.matches(request.oldPassword, admin.passwordHash))
            throw HttpException(HttpStatus.BAD_REQUEST, "Old password is incorrect")

        require(request.newPassword.isNotBlank()) { "New password must not be blank" }

        val updated = admin.copy(
            passwordHash = passwordEncoder.encode(request.newPassword)
        )
        val saved = adminRepository.save(updated)
        refreshTokenService.revokeAll(id)
        return saved.toDto(resolveStoreName(saved.storeId))
    }

    @Transactional
    suspend fun updateUsername(id: UUID, request: UpdateUsernameRequest): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())

        val username = request.username.trim()

        if (username == admin.username)
            throw HttpException(HttpStatus.BAD_REQUEST, "New username must differ from current username")

        if (!username.equals(admin.username, ignoreCase = true) && adminRepository.existsByUsername(username))
            throw ConflictException("Username '$username' is already taken")

        val updated = admin.copy(username = username)
        return adminRepository.save(updated).toDto(resolveStoreName(updated.storeId))
    }

    @Transactional
    suspend fun updateAdminStore(id: UUID, storeId: UUID?, actorOrgId: UUID): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())

        if (admin.role == AdminRole.ROLE_SUPER_ADMIN)
            throw HttpException(HttpStatus.BAD_REQUEST, "Cannot assign a store to a SUPER_ADMIN")

        requireSameOrg(admin, actorOrgId)

        val storeName = if (storeId != null) {
            val store = storeRepository.findById(storeId)
                ?: throw NotFoundException("Store", "id", storeId.toString())
            if (store.orgId != actorOrgId)
                throw ForbiddenException("Store is not in your organization")
            store.name
        } else null

        val updated = admin.copy(storeId = storeId)
        return adminRepository.save(updated).toDto(storeName)
    }

    @Transactional
    suspend fun deleteAdmin(id: UUID, requesterId: UUID, actorOrgId: UUID) {
        if (id == requesterId)
            throw ForbiddenException("Cannot delete your own account")

        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())

        requireSameOrg(admin, actorOrgId)

        if (admin.role == AdminRole.ROLE_SUPER_ADMIN) {
            val superAdminCount = adminRepository.countByOrgIdAndRole(actorOrgId, AdminRole.ROLE_SUPER_ADMIN)
            if (superAdminCount <= 1)
                throw ConflictException("Cannot delete the last SUPER_ADMIN account")
        }

        adminRepository.delete(admin)
        refreshTokenService.revokeAll(id)
    }

    @Transactional(readOnly = true)
    suspend fun listAdminsByStore(storeId: UUID, page: Int, size: Int, role: AdminRole? = null): AdminPageResponse {
        require(page >= 0) { "Page must be greater than or equal to 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }

        val totalItems = if (role != null) {
            adminRepository.countByStoreIdAndRole(storeId, role)
        } else {
            adminRepository.countByStoreId(storeId)
        }
        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        val offset = page.toLong() * size

        val items = if (offset >= totalItems) {
            emptyList()
        } else {
            val storeName = resolveStoreName(storeId)
            val admins = if (role != null) {
                adminRepository.findByStoreIdAndRolePaged(storeId, role, size.toLong(), offset)
            } else {
                adminRepository.findByStoreIdPaged(storeId, size.toLong(), offset)
            }
            admins.toList().map { it.toDto(storeName) }
        }

        return AdminPageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages
        )
    }

    @Transactional(readOnly = true)
    suspend fun listAdminsByOrg(orgId: UUID, page: Int, size: Int): AdminPageResponse {
        require(page >= 0) { "Page must be greater than or equal to 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }

        val totalItems = adminRepository.countByOrg(orgId)
        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        val offset = page.toLong() * size

        val items = if (offset >= totalItems) {
            emptyList()
        } else {
            val admins = adminRepository.findByOrgPaged(orgId, size.toLong(), offset).toList()
            val storeNames = resolveStoreNames(admins)
            admins.map { it.toDto(it.storeId?.let { id -> storeNames[id] }) }
        }

        return AdminPageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages
        )
    }

    suspend fun recordLoginAttempt(adminId: UUID, ipAddress: String, success: Boolean): LoginHistory {
        return loginHistoryRepository.save(
            LoginHistory(adminId = adminId, ipAddress = ipAddress, success = success)
        )
    }

    suspend fun getLoginHistory(adminId: UUID, limit: Int = 20): LoginHistoryPageResponse {
        val rows = loginHistoryQueryRepository.findRecentByAdminId(adminId, limit)
        val hasMore = rows.size > limit
        val items = rows.take(limit).map {
            LoginHistoryDto(
                id = it.id.toString(),
                ipAddress = it.ipAddress,
                success = it.success,
                createdAt = it.createdAt
            )
        }
        return LoginHistoryPageResponse(items = items, hasMore = hasMore)
    }
}
