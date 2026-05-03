package com.thomas.notiguide.domain.device.service

class DeviceBadRequestEnvelopeException(
    val error: String,
    val required: Int? = null
) : RuntimeException(error)

class PassiveDeviceConflictException(
    val publicId: String
) : RuntimeException(publicId)
