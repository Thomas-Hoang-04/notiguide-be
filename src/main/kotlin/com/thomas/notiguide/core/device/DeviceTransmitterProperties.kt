package com.thomas.notiguide.core.device

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "device.transmitter")
data class DeviceTransmitterProperties(
    val enabled: Boolean = false,
    val heartbeatIntervalSeconds: Long = 10,
    val heartbeatLivenessSeconds: Long = 30,
    val activeCacheSeconds: Long = 60,
    val maxRegisteredPerStore: Int = 3
) {
    init {
        require(heartbeatIntervalSeconds > 0) { "device.transmitter.heartbeat-interval-seconds must be positive" }
        require(heartbeatLivenessSeconds > 0) { "device.transmitter.heartbeat-liveness-seconds must be positive" }
        require(activeCacheSeconds > 0) { "device.transmitter.active-cache-seconds must be positive" }
        require(maxRegisteredPerStore > 0) { "device.transmitter.max-registered-per-store must be positive" }
    }
}
