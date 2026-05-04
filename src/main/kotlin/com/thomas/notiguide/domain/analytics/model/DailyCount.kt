package com.thomas.notiguide.domain.analytics.model

import java.time.LocalDate

data class DailyCount(
    val date: LocalDate,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long
)