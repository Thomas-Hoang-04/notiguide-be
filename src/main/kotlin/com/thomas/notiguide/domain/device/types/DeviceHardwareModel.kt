package com.thomas.notiguide.domain.device.types

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class DeviceHardwareModel(private val wireValue: String) {
    ESP_01("ESP-01"),
    ESP32_C3("ESP32-C3"),
    PT2272("PT2272");

    @JsonValue
    fun toWireValue(): String = wireValue

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromWireValue(value: String): DeviceHardwareModel =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown device hardware model '$value'")
    }
}
