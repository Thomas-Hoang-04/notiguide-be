package com.thomas.notiguide.domain.store.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.response.StorePageResponse
import com.thomas.notiguide.domain.store.dto.StoreSettingsDto
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreSettingsRequest
import com.thomas.notiguide.domain.store.service.StoreService
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/stores")
class StoreController(
    private val storeService: StoreService
) {

    @GetMapping
    suspend fun listStores(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StorePageResponse> {
        requireSuperAdmin(principal)
        val response = storeService.listStores(page, size)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{id}")
    suspend fun getStore(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreDto> {
        StoreAccessUtil.requireStoreAccess(principal, id)
        val dto = storeService.getStore(id)
        return ResponseEntity.ok(dto)
    }

    @PostMapping
    suspend fun createStore(
        @Valid @RequestBody request: CreateStoreRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreDto> {
        requireSuperAdmin(principal)
        val dto = storeService.createStore(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PutMapping("/{id}")
    suspend fun updateStore(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateStoreRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreDto> {
        StoreAccessUtil.requireStoreAccess(principal, id)
        if (!isSuperAdmin(principal)) {
            // Regular ADMINs may only toggle queue behavior flags
            if (request.name != null || request.addressProvided || request.isActive != null) {
                throw ForbiddenException("Only elevated admins can modify store name, address, or status")
            }
        }
        val dto = storeService.updateStore(id, request)
        return ResponseEntity.ok(dto)
    }

    @DeleteMapping("/{id}")
    suspend fun deleteStore(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        requireSuperAdmin(principal)
        storeService.deleteStore(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/settings")
    suspend fun getStoreSettings(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSettingsDto> {
        StoreAccessUtil.requireStoreAccess(principal, id)
        return ResponseEntity.ok(storeService.getStoreSettings(id))
    }

    @PutMapping("/{id}/settings")
    suspend fun updateStoreSettings(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateStoreSettingsRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSettingsDto> {
        StoreAccessUtil.requireStoreAccess(principal, id)
        return ResponseEntity.ok(storeService.updateStoreSettings(id, request))
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }

    private fun requireSuperAdmin(principal: AdminPrincipal) {
        if (!isSuperAdmin(principal))
            throw ForbiddenException("Only elevated admins can perform this action")
    }
}
