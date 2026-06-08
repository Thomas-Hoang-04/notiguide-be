package com.thomas.notiguide.domain.organization.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.organization.dto.OrganizationPublicDto
import com.thomas.notiguide.domain.organization.response.JoinCodeResponse
import com.thomas.notiguide.domain.organization.service.OrganizationService
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.shared.principal.AdminPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/orgs")
class OrganizationController(
    private val organizationService: OrganizationService,
    private val storeRepository: StoreRepository
) {
    @GetMapping("/me")
    suspend fun getMyOrg(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<OrganizationPublicDto> {
        val orgId = resolveOrgId(principal)
            ?: throw NotFoundException("Organization", "admin", principal.id.toString())
        return ResponseEntity.ok(organizationService.getOrganizationPublic(orgId))
    }

    @GetMapping("/me/join-code")
    suspend fun getJoinCode(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<JoinCodeResponse> {
        val orgId = requireOrgOwner(principal)
        return ResponseEntity.ok(JoinCodeResponse(organizationService.getOrganization(orgId).joinCode))
    }

    @PostMapping("/me/join-code/rotate")
    suspend fun rotateJoinCode(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<JoinCodeResponse> {
        val orgId = requireOrgOwner(principal)
        return ResponseEntity.ok(JoinCodeResponse(organizationService.rotateJoinCode(orgId).joinCode))
    }

    private suspend fun resolveOrgId(principal: AdminPrincipal): UUID? {
        if (principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }) {
            return principal.orgId
        }
        val storeId = principal.storeId ?: return null
        return storeRepository.findOrgIdByStoreId(storeId)
    }

    private fun requireOrgOwner(principal: AdminPrincipal) =
        principal.takeIf { it.authorities.any { a -> a.authority == AdminRole.ROLE_SUPER_ADMIN.name } }
            ?.orgId
            ?: throw ForbiddenException("Only organization owners can manage organization join codes")
}
