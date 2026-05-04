package com.thomas.notiguide.domain.device.response

import com.thomas.notiguide.domain.device.dto.DeviceDto

data class DeviceListResponse(
    val devices: List<DeviceDto>,
    val registered: Long? = null
)
