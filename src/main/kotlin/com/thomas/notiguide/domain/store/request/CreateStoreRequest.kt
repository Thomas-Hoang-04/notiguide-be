package com.thomas.notiguide.domain.store.request

data class CreateStoreRequest(
    val name: String,
    val address: String? = null
)
