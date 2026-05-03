package com.thomas.notiguide.domain.device.types

enum class DeviceLifecycleAction(
    val wireValue: String
) {
    SUSPEND("suspend"),
    RESUME("resume"),
    DECOMMISSION("decommission");

    companion object {
        fun fromWireValue(value: String): DeviceLifecycleAction? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}
