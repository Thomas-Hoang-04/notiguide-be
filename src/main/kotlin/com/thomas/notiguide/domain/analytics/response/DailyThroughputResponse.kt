package com.thomas.notiguide.domain.analytics.response

import com.thomas.notiguide.domain.analytics.model.DailyCount

data class DailyThroughputResponse(
    val range: String,
    val days: List<DailyCount>
)