package com.thomas.notiguide.domain.device.types

enum class DeviceFamily(val topicSegment: String) {
    RECEIVER("receiver"),
    TRANSMITTER("transmitter")
}
