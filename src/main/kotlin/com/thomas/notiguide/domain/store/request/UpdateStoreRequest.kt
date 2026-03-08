package com.thomas.notiguide.domain.store.request

data class UpdateStoreRequest(
    val name: String? = null,
    val address: String? = null,
    val isActive: Boolean? = null
)
