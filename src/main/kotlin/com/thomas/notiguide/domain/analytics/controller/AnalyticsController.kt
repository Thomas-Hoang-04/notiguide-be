package com.thomas.notiguide.domain.analytics.controller

import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.analytics.response.DailyThroughputResponse
import com.thomas.notiguide.domain.analytics.response.HourlyHeatmapResponse
import com.thomas.notiguide.domain.analytics.response.OverviewRealtimeResponse
import com.thomas.notiguide.domain.analytics.response.OverviewResponse
import com.thomas.notiguide.domain.analytics.response.PeakHoursResponse
import com.thomas.notiguide.domain.analytics.types.Period
import com.thomas.notiguide.domain.analytics.types.Range
import com.thomas.notiguide.domain.analytics.response.RealtimeStatsResponse
import com.thomas.notiguide.domain.analytics.response.StoreSummaryResponse
import com.thomas.notiguide.domain.analytics.response.WaitDistributionResponse
import com.thomas.notiguide.domain.analytics.service.AnalyticsQueryService
import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val analyticsQueryService: AnalyticsQueryService
) {

    // -- Overview (super-admin, cross-store) — must be declared before /{storeId} routes --

    @GetMapping("/overview/realtime")
    suspend fun getOverviewRealtime(
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<OverviewRealtimeResponse> {
        requireSuperAdmin(principal)
        return ResponseEntity.ok(analyticsQueryService.getOverviewRealtime())
    }

    @GetMapping("/overview/throughput")
    suspend fun getOverviewThroughput(
        @RequestParam range: Range,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DailyThroughputResponse> {
        requireSuperAdmin(principal)
        return ResponseEntity.ok(analyticsQueryService.getOverviewThroughput(range))
    }

    @GetMapping("/overview")
    suspend fun getOverview(
        @RequestParam period: Period,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<OverviewResponse> {
        requireSuperAdmin(principal)
        return ResponseEntity.ok(analyticsQueryService.getOverview(period))
    }

    // -- Store-specific (admin with store access) --

    @GetMapping("/{storeId}/realtime")
    suspend fun getRealtimeStats(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<RealtimeStatsResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getRealtimeStats(storeId))
    }

    @GetMapping("/{storeId}/summary")
    suspend fun getStoreSummary(
        @PathVariable storeId: UUID,
        @RequestParam period: Period,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<StoreSummaryResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getStoreSummary(storeId, period))
    }

    @GetMapping("/{storeId}/peak-hours")
    suspend fun getPeakHours(
        @PathVariable storeId: UUID,
        @RequestParam range: Range,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<PeakHoursResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getPeakHours(storeId, range))
    }

    @GetMapping("/{storeId}/throughput")
    suspend fun getDailyThroughput(
        @PathVariable storeId: UUID,
        @RequestParam range: Range,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DailyThroughputResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getDailyThroughput(storeId, range))
    }

    @GetMapping("/{storeId}/wait-distribution")
    suspend fun getWaitDistribution(
        @PathVariable storeId: UUID,
        @RequestParam period: Period,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<WaitDistributionResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getWaitDistribution(storeId, period))
    }

    @GetMapping("/{storeId}/heatmap")
    suspend fun getHourlyHeatmap(
        @PathVariable storeId: UUID,
        @RequestParam range: Range,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<HourlyHeatmapResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return ResponseEntity.ok(analyticsQueryService.getHourlyHeatmap(storeId, range))
    }

    private fun requireSuperAdmin(principal: AdminPrincipal) {
        if (principal.authorities.none { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }) {
            throw ForbiddenException("Only Super Admins can access cross-store analytics")
        }
    }
}
