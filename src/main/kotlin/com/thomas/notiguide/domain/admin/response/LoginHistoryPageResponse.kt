package com.thomas.notiguide.domain.admin.response

import com.thomas.notiguide.domain.admin.dto.LoginHistoryDto

data class LoginHistoryPageResponse(
    val items: List<LoginHistoryDto>,
    val hasMore: Boolean
)