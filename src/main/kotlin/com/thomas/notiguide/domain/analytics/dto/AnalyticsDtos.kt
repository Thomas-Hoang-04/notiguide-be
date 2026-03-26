package com.thomas.notiguide.domain.analytics.dto

import java.time.LocalDate
import java.util.UUID

data class RealtimeStatsResponse(
    val currentQueueSize: Long,
    val currentServingCount: Long,
    val ticketsIssuedToday: Long,
    val estimatedAvgWaitMinutes: Double?
)

data class StoreSummaryResponse(
    val period: String,
    val totalIssued: Long,
    val totalCompleted: Long,
    val totalCancelled: Long,
    val totalSkipped: Long,
    val avgWaitSeconds: Double?,
    val avgServiceSeconds: Double?,
    val medianWaitSeconds: Double?,
    val peakHour: Int?,
    val cancelRate: Double?,
    val skipRate: Double?
)

data class PeakHoursResponse(
    val range: String,
    val hours: List<HourlyCount>
)

data class HourlyCount(
    val hour: Int,
    val avgTickets: Double
)

data class DailyThroughputResponse(
    val range: String,
    val days: List<DailyCount>
)

data class DailyCount(
    val date: LocalDate,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long
)

data class OverviewRealtimeResponse(
    val activeStores: Long,
    val totalQueueSize: Long,
    val totalServingCount: Long,
    val totalIssuedToday: Long,
    val estimatedAvgWaitMinutes: Double?
)

data class OverviewResponse(
    val period: String,
    val totalStores: Long,
    val totalIssued: Long,
    val totalCompleted: Long,
    val avgWaitSeconds: Double?,
    val stores: List<StoreAnalyticsSummary>
)

data class StoreAnalyticsSummary(
    val storeId: UUID,
    val storeName: String,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long,
    val avgWaitSeconds: Double?
)

enum class Period {
    TODAY, WEEK, MONTH, QUARTER
}

enum class Range {
    D7, D30, D90
}

data class WaitDistributionResponse(
    val period: String,
    val buckets: List<WaitBucket>
)

data class WaitBucket(
    val label: String,
    val minMinutes: Int,
    val maxMinutes: Int?,
    val count: Long
)

data class HourlyHeatmapResponse(
    val range: String,
    val cells: List<HeatmapCell>
)

data class HeatmapCell(
    val dayOfWeek: Int,
    val hour: Int,
    val avgTickets: Double
)
