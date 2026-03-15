package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.dto.AdminPageResponse
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.CreateAdminRequest
import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.flow.toList
import com.thomas.notiguide.domain.admin.request.UpdatePasswordRequest
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
    private val storeRepository: StoreRepository
) {

    suspend fun getAdmin(id: UUID): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())
        return admin.toDto()
    }

    @Transactional
    suspend fun createAdmin(request: CreateAdminRequest, createdById: UUID): AdminDto {
        require(request.username.isNotBlank()) { "Username must not be blank" }
        require(request.password.isNotBlank()) { "Password must not be blank" }

        val normalizedUsername = request.username.lowercase()

        when (request.role) {
            AdminRole.ROLE_ADMIN -> {
                if (request.storeId == null)
                    throw HttpException(HttpStatus.BAD_REQUEST, "ROLE_ADMIN requires a storeId")
            }
            AdminRole.ROLE_SUPER_ADMIN -> {
                if (request.storeId != null)
                    throw HttpException(HttpStatus.BAD_REQUEST, "ROLE_SUPER_ADMIN must not have a storeId")
            }
        }

        if (adminRepository.existsByUsername(normalizedUsername))
            throw ConflictException("Username '$normalizedUsername' is already taken")

        if (request.storeId != null) {
            storeRepository.findById(request.storeId)
                ?: throw NotFoundException("Store", "id", request.storeId.toString())
        }

        val admin = Admin(
            username = normalizedUsername,
            passwordHash = passwordEncoder.encode(request.password),
            role = request.role,
            storeId = request.storeId,
            isVerified = false,
            createdBy = createdById
        )
        return adminRepository.save(admin).toDto()
    }

    @Transactional
    suspend fun verifyAdmin(adminId: UUID, verifierId: UUID): AdminDto {
        if (adminId == verifierId)
            throw ForbiddenException("Cannot verify your own account")

        val admin = adminRepository.findById(adminId)
            ?: throw NotFoundException("Admin", "id", adminId.toString())

        if (admin.isVerified)
            throw ConflictException("Admin '${admin.username}' is already verified")

        val verified = admin.copy(
            isVerified = true,
            verifiedBy = verifierId,
            verifiedAt = OffsetDateTime.now()
        )
        return adminRepository.save(verified).toDto()
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
        return adminRepository.save(updated).toDto()
    }

    @Transactional
    suspend fun deleteAdmin(id: UUID, requesterId: UUID) {
        if (id == requesterId)
            throw ForbiddenException("Cannot delete your own account")

        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())

        if (admin.role == AdminRole.ROLE_SUPER_ADMIN) {
            val superAdminCount = adminRepository.countByRole(AdminRole.ROLE_SUPER_ADMIN)
            if (superAdminCount <= 1)
                throw ConflictException("Cannot delete the last SUPER_ADMIN account")
        }

        adminRepository.delete(admin)
    }

    suspend fun listAdminsByStore(storeId: UUID, page: Int, size: Int): AdminPageResponse {
        require(page >= 0) { "Page must be greater than or equal to 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }

        val totalItems = adminRepository.countByStoreId(storeId)
        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        val offset = page.toLong() * size

        val items = if (offset >= totalItems) {
            emptyList()
        } else {
            adminRepository
                .findByStoreIdPaged(storeId, size.toLong(), offset)
                .toList()
                .map { it.toDto() }
        }

        return AdminPageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages
        )
    }

    suspend fun listAllAdmins(page: Int, size: Int): AdminPageResponse {
        require(page >= 0) { "Page must be greater than or equal to 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }

        val totalItems = adminRepository.count()
        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        val offset = page.toLong() * size

        val items = if (offset >= totalItems) {
            emptyList()
        } else {
            adminRepository
                .findAllPaged(size.toLong(), offset)
                .toList()
                .map { it.toDto() }
        }

        return AdminPageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages
        )
    }
}
