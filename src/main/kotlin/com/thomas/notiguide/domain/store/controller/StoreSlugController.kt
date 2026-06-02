package com.thomas.notiguide.domain.store.controller

import com.thomas.notiguide.domain.store.dto.StoreSlugDto
import com.thomas.notiguide.domain.store.response.StoreSlugListResponse
import com.thomas.notiguide.domain.store.request.CreateSlugRequest
import com.thomas.notiguide.domain.store.service.StoreSlugService
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/stores/{storeId}/slugs")
class StoreSlugController(
    private val storeSlugService: StoreSlugService
) {

    @GetMapping
    suspend fun list(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSlugListResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(storeSlugService.listSlugs(storeId))
    }

    @PostMapping
    suspend fun create(
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: CreateSlugRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSlugDto> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.status(HttpStatus.CREATED).body(storeSlugService.createAlias(storeId, request))
    }

    @PostMapping("/{slug}/retire")
    suspend fun retire(
        @PathVariable storeId: UUID,
        @PathVariable slug: String,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSlugDto> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(storeSlugService.retireAlias(storeId, slug))
    }

    @DeleteMapping("/{slug}")
    suspend fun delete(
        @PathVariable storeId: UUID,
        @PathVariable slug: String,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        storeSlugService.hardDeleteAlias(storeId, slug)
        return ResponseEntity.noContent().build()
    }
}
