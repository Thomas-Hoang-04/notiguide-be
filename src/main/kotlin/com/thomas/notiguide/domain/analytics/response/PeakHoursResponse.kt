package com.thomas.notiguide.domain.analytics.response

import com.thomas.notiguide.domain.analytics.model.HourlyCount

data class PeakHoursResponse(
    val range: String,
    val hours: List<HourlyCount>
)