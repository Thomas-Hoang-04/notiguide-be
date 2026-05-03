package com.thomas.notiguide.domain.device.types

enum class DeviceLifecycleAckStatus {
    PENDING,
    OK,
    IGNORED,
    REJECTED;

    companion object {
        fun fromWireValue(value: String): DeviceLifecycleAckStatus? =
            entries.firstOrNull { it.name == value.trim().uppercase() }
    }
}
