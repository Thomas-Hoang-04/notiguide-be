package com.thomas.notiguide.shared.principal

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import java.util.UUID

object StoreAccessUtil {

    fun requireStoreAccess(principal: AdminPrincipal, storeId: UUID) {
        if (principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }) return
        if (principal.storeId != storeId)
            throw ForbiddenException("You do not have access to this store")
    }
}
