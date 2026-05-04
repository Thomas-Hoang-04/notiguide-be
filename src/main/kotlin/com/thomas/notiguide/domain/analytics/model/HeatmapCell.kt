package com.thomas.notiguide.domain.analytics.model

data class HeatmapCell(
    val dayOfWeek: Int,
    val hour: Int,
    val avgTickets: Double
)
