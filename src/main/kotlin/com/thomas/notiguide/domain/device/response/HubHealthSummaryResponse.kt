package com.thomas.notiguide.domain.device.response

import com.thomas.notiguide.domain.device.dto.HubWarningDto

data class HubHealthSummaryResponse(
    val totalHubs: Int,
    val onlineHubs: Int,
    val offlineHubs: Int,
    val warnings: List<HubWarningDto>
)

