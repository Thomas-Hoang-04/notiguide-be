package com.thomas.notiguide.core.mqtt

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mqtt")
data class MqttProperties(
    val broker: String,
    val clientId: String = "notiguide",
    val username: String,
    val password: String,
    val topicPrefix: String = "notiguide",
    val qos: Int = 1,
    val keepAliveSeconds: Int = 60,
    val connectionTimeoutSeconds: Int = 30,
    val autoReconnect: Boolean = true,
    val maxReconnectDelaySeconds: Int = 128
) {
    init {
        require(qos in 0..2) { "MQTT QoS must be 0, 1, or 2" }
        require(connectionTimeoutSeconds in 1..300) { "MQTT connection timeout must be between 1 and 300 seconds" }
    }
}
