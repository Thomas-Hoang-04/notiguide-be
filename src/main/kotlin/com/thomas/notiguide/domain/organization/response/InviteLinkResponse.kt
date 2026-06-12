package com.thomas.notiguide.domain.organization.response

import com.thomas.notiguide.domain.organization.dto.InviteLinkUse

/** `token`/`expiresAt` both null = "no active link" (a normal state, always HTTP 200). */
data class InviteLinkResponse(
    val token: String?,
    val expiresAt: String?,
    val recentUses: List<InviteLinkUse> = emptyList()
)
