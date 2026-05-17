package com.thomas.notiguide.domain.device.response

data class UsbDispatchPayloadResponse(
    val receiverPublicId: String,
    val band: String,
    val rfCodeHex: String,
    val rfCodeBits: Int,
    val protoAny: Boolean
)
