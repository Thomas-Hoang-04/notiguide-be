package com.thomas.notiguide.domain.device.controller

class DeviceBadRequestEnvelopeException(
    val error: String,
    val required: Int? = null,
    val detailMessage: String? = null
) : RuntimeException(detailMessage ?: error)

class PassiveDeviceConflictException(
    val publicId: String
) : RuntimeException(publicId)

class DeviceConflictEnvelopeException(
    val error: String,
    val publicId: String? = null
) : RuntimeException(error)

class DeviceServiceUnavailableEnvelopeException(
    val error: String,
    val detailMessage: String? = null
) : RuntimeException(detailMessage ?: error)
