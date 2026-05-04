package com.thomas.notiguide.domain.analytics.model

data class WaitBucket(
    val label: String,
    val minMinutes: Int,
    val maxMinutes: Int?,
    val count: Long
)