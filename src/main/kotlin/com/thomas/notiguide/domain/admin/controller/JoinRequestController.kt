package com.thomas.notiguide.domain.admin.controller

import com.thomas.notiguide.core.exception.ConflictException
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.dto.JoinRequestDto
import com.thomas.notiguide.domain.admin.request.ApproveJoinRequest
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admins/requests")
class JoinRequestController(
    private val joinRequestService: JoinRequestService,
    private val storeRepository: StoreRepository
) {
    @GetMapping
    suspend fun list(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<List<JoinRequestDto>> {
        val items = if (isSuperAdmin(principal)) {
            val orgId = principal.orgId ?: throw ForbiddenException("No organization assigned")
            joinRequestService.listByOrg(orgId)
        } else {
            val storeId = principal.storeId
                ?: throw ForbiddenException("Store-scoped admins need an assigned store")
            joinRequestService.listByStore(storeId)
        }
        return ResponseEntity.ok(items)
    }

    @PostMapping("/{requestId}/approve")
    suspend fun approve(
        @PathVariable requestId: String,
        @RequestBody(required = false) body: ApproveJoinRequest?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        val payload = joinRequestService.get(requestId)
            ?: throw NotFoundException("JoinRequest", "id", requestId)

        val assignStoreId: UUID = when (payload.targetType) {
            JoinRequestService.TargetType.ORG -> {
                val orgId = principal.orgId?.takeIf { isSuperAdmin(principal) }
                    ?: throw ForbiddenException("Only the organization owner can approve this request")
                if (UUID.fromString(payload.targetId) != orgId)
                    throw ForbiddenException("Request is not for your organization")
                val storeId = body?.storeId
                    ?: throw ConflictException("Select a store in your organization to assign")
                val store = storeRepository.findById(storeId)
                    ?: throw NotFoundException("Store", "id", storeId.toString())
                if (store.orgId != orgId)
                    throw ForbiddenException("Store is not in your organization")
                storeId
            }
            JoinRequestService.TargetType.STORE -> {
                val storeId = principal.storeId
                    ?: throw ForbiddenException("Only the store's admin can approve this request")
                if (UUID.fromString(payload.targetId) != storeId)
                    throw ForbiddenException("Request is not for your store")
                if (!principal.isVerified)
                    throw ForbiddenException("Only a verified admin can approve co-owners")
                storeId
            }
        }
        joinRequestService.approve(requestId, assignStoreId, principal.id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{requestId}/reject")
    suspend fun reject(
        @PathVariable requestId: String,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        val payload = joinRequestService.get(requestId)
            ?: return ResponseEntity.noContent().build()
        // Same scope check as approve
        when (payload.targetType) {
            JoinRequestService.TargetType.ORG -> {
                val orgId = principal.orgId?.takeIf { isSuperAdmin(principal) }
                    ?: throw ForbiddenException("Only the organization owner can reject this request")
                if (UUID.fromString(payload.targetId) != orgId)
                    throw ForbiddenException("Request is not for your organization")
            }
            JoinRequestService.TargetType.STORE -> {
                val storeId = principal.storeId
                    ?: throw ForbiddenException("Only the store's admin can reject this request")
                if (UUID.fromString(payload.targetId) != storeId)
                    throw ForbiddenException("Request is not for your store")
            }
        }
        joinRequestService.reject(requestId)
        return ResponseEntity.noContent().build()
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }
}
