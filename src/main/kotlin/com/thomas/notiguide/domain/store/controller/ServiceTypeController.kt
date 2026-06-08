package com.thomas.notiguide.domain.store.controller

import com.thomas.notiguide.domain.store.dto.ServiceTypeDto
import com.thomas.notiguide.domain.store.request.CreateServiceTypeRequest
import com.thomas.notiguide.domain.store.request.UpdateServiceTypeRequest
import com.thomas.notiguide.domain.store.service.ServiceTypeService
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/stores/{storeId}/service-types")
class ServiceTypeController(
    private val serviceTypeService: ServiceTypeService,
    private val storeAccess: StoreAccessService
) {

    @GetMapping
    suspend fun list(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<List<ServiceTypeDto>> {
        storeAccess.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(serviceTypeService.listServiceTypes(storeId))
    }

    @PostMapping
    suspend fun create(
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: CreateServiceTypeRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<ServiceTypeDto> {
        storeAccess.requireStoreAccess(principal, storeId)
        val dto = serviceTypeService.createServiceType(storeId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable storeId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateServiceTypeRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<ServiceTypeDto> {
        storeAccess.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(serviceTypeService.updateServiceType(storeId, id, request))
    }

    @DeleteMapping("/{id}")
    suspend fun delete(
        @PathVariable storeId: UUID,
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        storeAccess.requireStoreAccess(principal, storeId)
        serviceTypeService.deleteServiceType(storeId, id)
        return ResponseEntity.noContent().build()
    }
}
