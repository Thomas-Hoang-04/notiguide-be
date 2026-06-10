package com.thomas.notiguide.core.device

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "device.transmitter")
data class DeviceTransmitterProperties(
    val enabled: Boolean = true,
    val heartbeatIntervalSeconds: Long = 10,
    val heartbeatLivenessSeconds: Long = 30,
    val activeCacheSeconds: Long = 60,
    val maxRegisteredPerStore: Int = 3,
    val diagnosticsCacheSeconds: Long = 45,
    val dispatchAckTimeoutSeconds: Long = 30
) {
    init {
        require(heartbeatIntervalSeconds > 0) { "Heartbeat intervals must be positive" }
        require(heartbeatLivenessSeconds > 0) { "Heartbeat keep-alive period must be positive" }
        require(activeCacheSeconds > 0) { "Cache TTL must be positive" }
        require(maxRegisteredPerStore > 0) { "Max active transmitter count must be positive" }
        require(diagnosticsCacheSeconds > heartbeatIntervalSeconds) { "Diagnostic cache must exceed heartbeat interval" }
        require(dispatchAckTimeoutSeconds > 0) { "Dispatch ack timeout must be positive" }
    }
}
