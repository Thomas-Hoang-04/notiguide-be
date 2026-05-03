package com.thomas.notiguide.domain.device.dto

data class DeviceListResponse(
    val devices: List<DeviceDto>,
    val registered: Long? = null
)
