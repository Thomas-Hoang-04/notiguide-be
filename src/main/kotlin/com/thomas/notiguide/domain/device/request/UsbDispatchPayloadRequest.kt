package com.thomas.notiguide.domain.device.request

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class UsbDispatchPayloadRequest(
    @field:NotNull
    val storeId: UUID,
    @field:NotNull
    val deviceId: UUID,
    @field:NotNull
    val action: UsbDispatchAction
)

enum class UsbDispatchAction {
    CALL,
    STOP;

    @JsonValue
    fun toWireValue(): String = name.lowercase()

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromWireValue(value: String): UsbDispatchAction =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("invalid_usb_dispatch_action")
    }
}
