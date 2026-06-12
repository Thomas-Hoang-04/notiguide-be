package com.thomas.notiguide.domain.organization.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.domain.admin.service.InviteLinkService
import com.thomas.notiguide.domain.admin.service.JoinRequestService
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.organization.dto.OrganizationPublicDto
import com.thomas.notiguide.domain.organization.response.InviteLinkResponse
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
    private val storeRepository: StoreRepository,
    private val inviteLinkService: InviteLinkService
) {
    @GetMapping("/me")
    suspend fun getMyOrg(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<OrganizationPublicDto> {
        val orgId = resolveOrgId(principal)
            ?: throw NotFoundException("Organization", "admin", principal.id.toString())
        return ResponseEntity.ok(organizationService.getOrganizationPublic(orgId))
    }

    private suspend fun resolveOrgId(principal: AdminPrincipal): UUID? {
        if (principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }) {
            return principal.orgId
        }
        val storeId = principal.storeId ?: return null
        return storeRepository.findOrgIdByStoreId(storeId)
    }

    @GetMapping("/me/invite-link")
    suspend fun getInviteLink(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<InviteLinkResponse> {
        val orgId = requireOrgOwner(principal)
        return ResponseEntity.ok(composeLinkResponse(orgId, inviteLinkService.getActive(JoinRequestService.TargetType.ORG, orgId)))
    }

    @PostMapping("/me/invite-link/rotate")
    suspend fun rotateInviteLink(@AuthenticationPrincipal principal: AdminPrincipal): ResponseEntity<InviteLinkResponse> {
        val orgId = requireOrgOwner(principal)
        return ResponseEntity.ok(composeLinkResponse(orgId, inviteLinkService.regenerate(JoinRequestService.TargetType.ORG, orgId)))
    }

    private suspend fun composeLinkResponse(orgId: UUID, link: InviteLinkResponse?): InviteLinkResponse {
        val base = link ?: InviteLinkResponse(token = null, expiresAt = null)
        return base.copy(recentUses = inviteLinkService.getRecentUses(JoinRequestService.TargetType.ORG, orgId))
    }

    private fun requireOrgOwner(principal: AdminPrincipal) =
        principal.takeIf { it.authorities.any { a -> a.authority == AdminRole.ROLE_SUPER_ADMIN.name } }
            ?.orgId
            ?: throw ForbiddenException("Only organization owners can manage organization invites")
}
