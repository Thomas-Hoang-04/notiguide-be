package com.thomas.notiguide.domain.queue.response

import com.thomas.notiguide.domain.device.dto.DeviceDto

data class QueueDispatchAvailabilityResponse(
    val devices: List<DeviceDto>,
    val dispatchReady: Boolean,
    val error: String? = null,
    val maxHubsPerStore: Int? = null
)
