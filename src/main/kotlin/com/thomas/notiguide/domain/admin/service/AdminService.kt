package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.CreateAdminRequest
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
    private val passwordEncoder: PasswordEncoder
) {

    suspend fun getAdmin(id: UUID): AdminDto {
        val admin = adminRepository.findById(id)
            ?: throw NotFoundException("Admin", "id", id.toString())
        return admin.toDto()
    }

    @Transactional
    suspend fun createAdmin(request: CreateAdminRequest): AdminDto {
        require(request.username.isNotBlank()) { "Username must not be blank" }
        require(request.password.isNotBlank()) { "Password must not be blank" }

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

        if (adminRepository.existsByUsername(request.username))
            throw ConflictException("Username '${request.username}' is already taken")

        val admin = Admin(
            username = request.username,
            passwordHash = passwordEncoder.encode(request.password),
            role = request.role,
            storeId = request.storeId,
            isVerified = request.role == AdminRole.ROLE_SUPER_ADMIN
        )
        return adminRepository.save(admin).toDto()
    }

    @Transactional
    suspend fun verifyAdmin(adminId: UUID, verifierId: UUID): AdminDto {
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

        adminRepository.delete(admin)
    }

    suspend fun listAdminsByStore(storeId: UUID): List<AdminDto> =
        adminRepository.findByStoreId(storeId).toList().map { it.toDto() }
}
