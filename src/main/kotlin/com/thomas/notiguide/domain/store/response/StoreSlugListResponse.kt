package com.thomas.notiguide.domain.store.response

import com.thomas.notiguide.domain.store.dto.StoreSlugDto

data class StoreSlugListResponse(
    val items: List<StoreSlugDto>,
    val activeCount: Int,
    val activeMax: Int,
    val graceCount: Int,
    val graceMax: Int
)