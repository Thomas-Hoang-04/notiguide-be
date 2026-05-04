package com.thomas.notiguide.domain.admin.response

import com.thomas.notiguide.domain.admin.dto.AdminDto

data class AdminPageResponse(
    val items: List<AdminDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int
)
