package com.thomas.notiguide.domain.analytics.response

import com.thomas.notiguide.domain.analytics.model.HeatmapCell

data class HourlyHeatmapResponse(
    val range: String,
    val cells: List<HeatmapCell>
)