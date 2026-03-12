package com.thomas.notiguide.domain.store.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.store.dto.StoreDto
import com.thomas.notiguide.domain.store.dto.StorePageResponse
import com.thomas.notiguide.domain.store.request.CreateStoreRequest
import com.thomas.notiguide.domain.store.request.UpdateStoreRequest
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
        requireSuperAdmin(principal)
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

    private fun requireSuperAdmin(principal: AdminPrincipal) {
        if (principal.authorities.none { it.authority == AdminRole.ROLE_SUPER_ADMIN.name })
            throw ForbiddenException("Only elevated admins can perform this action")
    }
}
