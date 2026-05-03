package com.thomas.notiguide.domain.device.types

enum class DeviceKind {
    RECEIVER_433M,
    RECEIVER_433M_PASSIVE,
    RECEIVER_2_4G,
    TRANSMITTER_HUB;

    fun is433Band(): Boolean = this == RECEIVER_433M || this == RECEIVER_433M_PASSIVE

    fun is24Band(): Boolean = this == RECEIVER_2_4G

    fun isPassive(): Boolean = this == RECEIVER_433M_PASSIVE

    fun isHub(): Boolean = this == TRANSMITTER_HUB
}
