package com.thomas.notiguide.domain.admin.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.dto.AdminDto
import com.thomas.notiguide.domain.admin.dto.AdminPageResponse
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.admin.request.CreateAdminRequest
import com.thomas.notiguide.domain.admin.request.UpdatePasswordRequest
import com.thomas.notiguide.domain.admin.service.AdminService
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admins")
class AdminController(
    private val adminService: AdminService
) {

    @GetMapping("/me")
    suspend fun getMe(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<AdminDto> {
        val dto = adminService.getAdmin(principal.id)
        return ResponseEntity.ok(dto)
    }

    @PostMapping
    suspend fun createAdmin(
        @Valid @RequestBody request: CreateAdminRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<AdminDto> {
        requireSuperAdmin(principal)
        val dto = adminService.createAdmin(request, principal.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PatchMapping("/{id}/verify")
    suspend fun verifyAdmin(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<AdminDto> {
        requireSuperAdmin(principal)
        val dto = adminService.verifyAdmin(id, principal.id)
        return ResponseEntity.ok(dto)
    }

    @PatchMapping("/{id}/password")
    suspend fun updatePassword(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdatePasswordRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<AdminDto> {
        if (principal.id != id)
            throw ForbiddenException("You can only update your own password")
        val dto = adminService.updatePassword(id, request)
        return ResponseEntity.ok(dto)
    }

    @DeleteMapping("/{id}")
    suspend fun deleteAdmin(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        requireSuperAdmin(principal)
        adminService.deleteAdmin(id, principal.id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping
    suspend fun listAdmins(
        @RequestParam(required = false) storeId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<AdminPageResponse> {
        val admins = if (storeId == null) {
            requireSuperAdmin(principal)
            adminService.listAllAdmins(page, size)
        } else {
            StoreAccessUtil.requireStoreAccess(principal, storeId)
            adminService.listAdminsByStore(storeId, page, size)
        }
        return ResponseEntity.ok(admins)
    }

    private fun requireSuperAdmin(principal: AdminPrincipal) {
        if (principal.authorities.none { it.authority == AdminRole.ROLE_SUPER_ADMIN.name })
            throw ForbiddenException("Only elevated admins can perform this action")
    }
}
