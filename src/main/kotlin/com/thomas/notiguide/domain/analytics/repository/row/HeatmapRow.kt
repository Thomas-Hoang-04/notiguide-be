package com.thomas.notiguide.domain.analytics.repository.row

data class HeatmapRow(
    val dayOfWeek: Int,
    val hour: Int,
    val avgTickets: Double
)