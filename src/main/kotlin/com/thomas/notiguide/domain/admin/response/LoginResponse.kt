package com.thomas.notiguide.domain.admin.response

import com.thomas.notiguide.domain.admin.dto.AdminDto

data class LoginResponse(
    val admin: AdminDto,
    val sessionId: String? = null,
    /**
     * Short-lived (~60s) one-shot opaque token the client can POST to
     * `/api/auth/abort` to roll back this login's server-side artifacts
     * (session row, refresh token, login_history entry) when the
     * Set-Cookie response did not reach the browser. Login does not return
     * success unless this rollback capability was provisioned.
     */
    val abortToken: String
)
