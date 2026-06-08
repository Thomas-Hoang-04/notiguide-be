package com.thomas.notiguide.shared.principal

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.store.repository.StoreRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class StoreAccessService(
    private val storeRepository: StoreRepository
) {
    /**
     * SUPER_ADMIN may act on a store only when it belongs to their org.
     * ADMIN may act only on their single assigned store.
     */
    suspend fun requireStoreAccess(principal: AdminPrincipal, storeId: UUID) {
        if (isSuperAdmin(principal)) {
            val orgId = principal.orgId
                ?: throw ForbiddenException("You do not have access to this store")
            val storeOrgId = storeRepository.findOrgIdByStoreId(storeId)
            if (storeOrgId == null || storeOrgId != orgId)
                throw ForbiddenException("You do not have access to this store")
            return
        }
        if (principal.storeId != storeId)
            throw ForbiddenException("You do not have access to this store")
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }
}
