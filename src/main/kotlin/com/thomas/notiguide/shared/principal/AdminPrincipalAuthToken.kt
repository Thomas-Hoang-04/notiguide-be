package com.thomas.notiguide.shared.principal

import org.springframework.security.authentication.AbstractAuthenticationToken

class AdminPrincipalAuthToken(
    private val _principal: AdminPrincipal
) : AbstractAuthenticationToken(_principal.authorities) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): AdminPrincipal = _principal
}
