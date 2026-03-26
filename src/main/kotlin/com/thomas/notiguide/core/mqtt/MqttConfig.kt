package com.thomas.notiguide.core.mqtt

import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

@Configuration
@ConditionalOnProperty(prefix = "mqtt", name = ["broker"], matchIfMissing = false)
class MqttConfig(private val props: MqttProperties) {

    @Bean
    fun mqttConnectionOptions(): MqttConnectionOptions =
        MqttConnectionOptions().apply {
            isCleanStart = true
            keepAliveInterval = props.keepAliveSeconds
            connectionTimeout = props.connectionTimeoutSeconds
            isAutomaticReconnect = props.autoReconnect
            maxReconnectDelay = props.maxReconnectDelaySeconds

            if (!props.username.isNullOrBlank()) {
                userName = props.username
            }
            if (!props.password.isNullOrBlank()) {
                password = props.password.toByteArray()
            }
        }

    @Bean
    fun mqttAsyncClient(): MqttAsyncClient {
        val uniqueClientId = "${props.clientId}-${UUID.randomUUID().toString().take(8)}"
        return MqttAsyncClient(props.broker, uniqueClientId, MemoryPersistence())
    }
}
