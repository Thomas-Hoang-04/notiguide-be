package com.thomas.notiguide.support

import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.AdminPrincipalAuthToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

object TestPrincipals {
    fun authToken(
        role: AdminRole = AdminRole.ROLE_ADMIN,
        id: UUID = UUID.randomUUID(),
        storeId: UUID? = null,
        orgId: UUID? = null,
    ): AdminPrincipalAuthToken {
        val principal = AdminPrincipal(
            id,
            "tester",
            "",
            listOf(SimpleGrantedAuthority(role.name)),
            orgId,
            storeId,
            true,
        )
        return AdminPrincipalAuthToken(principal)
    }
}
