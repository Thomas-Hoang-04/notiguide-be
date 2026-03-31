package com.thomas.notiguide.domain.analytics.service

import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.domain.analytics.dto.DailyCount
import com.thomas.notiguide.domain.analytics.dto.DailyThroughputResponse
import com.thomas.notiguide.domain.analytics.dto.HeatmapCell
import com.thomas.notiguide.domain.analytics.dto.HourlyCount
import com.thomas.notiguide.domain.analytics.dto.HourlyHeatmapResponse
import com.thomas.notiguide.domain.analytics.dto.OverviewRealtimeResponse
import com.thomas.notiguide.domain.analytics.dto.OverviewResponse
import com.thomas.notiguide.domain.analytics.dto.PeakHoursResponse
import com.thomas.notiguide.domain.analytics.dto.Period
import com.thomas.notiguide.domain.analytics.dto.Range
import com.thomas.notiguide.domain.analytics.dto.RealtimeStatsResponse
import com.thomas.notiguide.domain.analytics.dto.StoreAnalyticsSummary
import com.thomas.notiguide.domain.analytics.dto.StoreSummaryResponse
import com.thomas.notiguide.domain.analytics.dto.WaitBucket
import com.thomas.notiguide.domain.analytics.dto.WaitDistributionResponse
import com.thomas.notiguide.domain.analytics.repository.AnalyticsEventRepository
import com.thomas.notiguide.domain.queue.repository.RedisCounterRepository
import com.thomas.notiguide.domain.queue.repository.RedisQueueRepository
import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.getAndAwait
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@Service
class AnalyticsQueryService(
    private val analyticsEventRepository: AnalyticsEventRepository,
    private val redisQueueRepository: RedisQueueRepository,
    private val redisCounterRepository: RedisCounterRepository,
    private val storeRepository: StoreRepository,
    private val redis: ReactiveRedisTemplate<String, String>,
    appProperties: AppProperties
) {
    private val storeTimezone: ZoneId = ZoneId.of(appProperties.timezone)

    companion object {
        private const val AVG_SERVICE_SAMPLE_SIZE = 20
        private const val COLD_START_THRESHOLD = 5
        private val AVG_SERVICE_CACHE_TTL: Duration = Duration.ofMinutes(5)
    }

    suspend fun getRealtimeStats(storeId: UUID): RealtimeStatsResponse {
        val queueSize = redisQueueRepository.getQueueSize(storeId)
        val servingCount = redisQueueRepository.getServingCount(storeId)
        val issuedToday = redisCounterRepository.getCurrentCount(storeId)
        val avgWait = getEstimatedAvgWaitMinutes(storeId)

        return RealtimeStatsResponse(
            currentQueueSize = queueSize,
            currentServingCount = servingCount,
            ticketsIssuedToday = issuedToday,
            estimatedAvgWaitMinutes = avgWait
        )
    }

    suspend fun getOverviewRealtime(): OverviewRealtimeResponse {
        val stores = storeRepository.findAllActive().toList()
        var totalQueue = 0L
        var totalServing = 0L
        var totalIssued = 0L
        var activeStores = 0L

        for (store in stores) {
            val queueSize = redisQueueRepository.getQueueSize(store.id!!)
            val servingCount = redisQueueRepository.getServingCount(store.id)
            val issued = redisCounterRepository.getCurrentCount(store.id)

            totalQueue += queueSize
            totalServing += servingCount
            totalIssued += issued

            if (queueSize > 0 || servingCount > 0) {
                activeStores++
            }
        }

        return OverviewRealtimeResponse(
            activeStores = activeStores,
            totalQueueSize = totalQueue,
            totalServingCount = totalServing,
            totalIssuedToday = totalIssued,
            estimatedAvgWaitMinutes = null
        )
    }

    suspend fun getStoreSummary(storeId: UUID, period: Period): StoreSummaryResponse {
        val (from, to) = periodToRange(period)
        val summary = analyticsEventRepository.getSummary(storeId, from, to)

        return StoreSummaryResponse(
            period = period.name.lowercase(),
            totalIssued = summary.totalIssued,
            totalCompleted = summary.totalCompleted,
            totalCancelled = summary.totalCancelled,
            totalSkipped = summary.totalSkipped,
            avgWaitSeconds = summary.avgWaitSeconds,
            avgServiceSeconds = summary.avgServiceSeconds,
            medianWaitSeconds = summary.medianWaitSeconds,
            peakHour = summary.peakHour,
            cancelRate = summary.cancelRate,
            skipRate = summary.skipRate
        )
    }

    suspend fun getPeakHours(storeId: UUID, range: Range): PeakHoursResponse {
        val (from, to) = rangeToRange(range)
        val rows = analyticsEventRepository.getHourlyDistribution(storeId, from, to)

        // Fill in missing hours with 0
        val hourMap = rows.associateBy { it.hour }
        val hours = (0..23).map { h ->
            HourlyCount(
                hour = h,
                avgTickets = hourMap[h]?.avgTickets ?: 0.0
            )
        }

        return PeakHoursResponse(
            range = range.name.lowercase(),
            hours = hours
        )
    }

    suspend fun getDailyThroughput(storeId: UUID, range: Range): DailyThroughputResponse {
        val (from, to) = rangeToRange(range)
        val rows = analyticsEventRepository.getDailyThroughput(storeId, from, to)

        return DailyThroughputResponse(
            range = range.name.lowercase(),
            days = rows.map { r ->
                DailyCount(
                    date = r.date,
                    issued = r.issued,
                    completed = r.completed,
                    cancelled = r.cancelled,
                    skipped = r.skipped
                )
            }
        )
    }

    suspend fun getOverview(period: Period): OverviewResponse {
        val (from, to) = periodToRange(period)
        val rows = analyticsEventRepository.getOverview(from, to)

        return OverviewResponse(
            period = period.name.lowercase(),
            totalStores = rows.size.toLong(),
            totalIssued = rows.sumOf { it.issued },
            totalCompleted = rows.sumOf { it.completed },
            avgWaitSeconds = rows.mapNotNull { it.avgWaitSeconds }.average().takeIf { !it.isNaN() },
            stores = rows.map { r ->
                StoreAnalyticsSummary(
                    storeId = r.storeId,
                    storeName = r.storeName,
                    issued = r.issued,
                    completed = r.completed,
                    cancelled = r.cancelled,
                    skipped = r.skipped,
                    avgWaitSeconds = r.avgWaitSeconds
                )
            }
        )
    }

    suspend fun getOverviewThroughput(range: Range): DailyThroughputResponse {
        val (from, to) = rangeToRange(range)
        val rows = analyticsEventRepository.getOverviewDailyThroughput(from, to)

        return DailyThroughputResponse(
            range = range.name.lowercase(),
            days = rows.map { r ->
                DailyCount(
                    date = r.date,
                    issued = r.issued,
                    completed = r.completed,
                    cancelled = r.cancelled,
                    skipped = r.skipped
                )
            }
        )
    }

    suspend fun getWaitDistribution(storeId: UUID, period: Period): WaitDistributionResponse {
        val (from, to) = periodToRange(period)
        val rows = analyticsEventRepository.getWaitDistribution(storeId, from, to)

        val labels = listOf("0-5", "5-10", "10-15", "15-20", "20+")
        val bounds = listOf(0 to 5, 5 to 10, 10 to 15, 15 to 20, 20 to null)
        val rowMap = rows.associateBy { it.bucket }

        val buckets = labels.mapIndexed { i, label ->
            WaitBucket(
                label = label,
                minMinutes = bounds[i].first,
                maxMinutes = bounds[i].second,
                count = rowMap[i]?.count ?: 0L
            )
        }

        return WaitDistributionResponse(
            period = period.name.lowercase(),
            buckets = buckets
        )
    }

    suspend fun getHourlyHeatmap(storeId: UUID, range: Range): HourlyHeatmapResponse {
        val (from, to) = rangeToRange(range)
        val rows = analyticsEventRepository.getHourlyHeatmap(storeId, from, to)

        val cellMap = rows.associateBy { it.dayOfWeek to it.hour }
        val cells = (1..7).flatMap { dow ->
            (0..23).map { hour ->
                HeatmapCell(
                    dayOfWeek = dow,
                    hour = hour,
                    avgTickets = cellMap[dow to hour]?.avgTickets ?: 0.0
                )
            }
        }

        return HourlyHeatmapResponse(
            range = range.name.lowercase(),
            cells = cells
        )
    }

    suspend fun getEstimatedWaitMinutes(storeId: UUID, positionInQueue: Long): Long? {
        val avgServiceSeconds = getCachedAvgServiceDuration(storeId) ?: return null
        val totalSeconds = positionInQueue * avgServiceSeconds
        val minutes = (totalSeconds / 60.0).toLong()
        return maxOf(1, minutes)
    }

    private suspend fun getEstimatedAvgWaitMinutes(storeId: UUID): Double? {
        val avgSeconds = getCachedAvgServiceDuration(storeId) ?: return null
        return avgSeconds / 60.0
    }

    private suspend fun getCachedAvgServiceDuration(storeId: UUID): Double? {
        val cacheKey = RedisKeyManager.avgServiceDuration(storeId)
        val cached = redis.opsForValue().getAndAwait(cacheKey)
        if (cached != null) {
            return cached.toDoubleOrNull()
        }

        val durations = analyticsEventRepository.getRecentServiceDurations(storeId, AVG_SERVICE_SAMPLE_SIZE)
        if (durations.size < COLD_START_THRESHOLD) return null

        // Trimmed mean: exclude top and bottom 10%
        val sorted = durations.sorted()
        val trimCount = (sorted.size * 0.1).toInt()
        val trimmed = if (trimCount > 0 && sorted.size > trimCount * 2) {
            sorted.subList(trimCount, sorted.size - trimCount)
        } else {
            sorted
        }

        val avg = trimmed.average()

        redis.opsForValue().set(cacheKey, avg.toString(), AVG_SERVICE_CACHE_TTL).awaitSingleOrNull()

        return avg
    }

    private fun periodToRange(period: Period): Pair<OffsetDateTime, OffsetDateTime> {
        val now = OffsetDateTime.now(storeTimezone)
        val today = LocalDate.now(storeTimezone)
        val to = now
        val from = when (period) {
            Period.TODAY -> today.atStartOfDay(storeTimezone).toOffsetDateTime()
            Period.WEEK -> today.minusDays(7).atStartOfDay(storeTimezone).toOffsetDateTime()
            Period.MONTH -> today.minusDays(30).atStartOfDay(storeTimezone).toOffsetDateTime()
            Period.QUARTER -> today.minusDays(90).atStartOfDay(storeTimezone).toOffsetDateTime()
        }
        return from to to
    }

    private fun rangeToRange(range: Range): Pair<OffsetDateTime, OffsetDateTime> {
        val now = OffsetDateTime.now(storeTimezone)
        val today = LocalDate.now(storeTimezone)
        val to = now
        val from = when (range) {
            Range.D7 -> today.minusDays(7).atStartOfDay(storeTimezone).toOffsetDateTime()
            Range.D30 -> today.minusDays(30).atStartOfDay(storeTimezone).toOffsetDateTime()
            Range.D90 -> today.minusDays(90).atStartOfDay(storeTimezone).toOffsetDateTime()
        }
        return from to to
    }
}
