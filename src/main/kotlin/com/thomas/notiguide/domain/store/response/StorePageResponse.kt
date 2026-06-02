package com.thomas.notiguide.domain.store.response

import com.thomas.notiguide.domain.store.dto.StoreDto

data class StorePageResponse(
    val items: List<StoreDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int
)
