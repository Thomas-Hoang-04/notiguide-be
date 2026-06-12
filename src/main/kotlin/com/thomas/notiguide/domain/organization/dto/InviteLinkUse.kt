package com.thomas.notiguide.domain.organization.dto

/** One usage-trail entry: a join submission made through an invitation link. */
data class InviteLinkUse(
    val username: String = "",
    val usedAt: String = "",
    val linkId: String = ""
)