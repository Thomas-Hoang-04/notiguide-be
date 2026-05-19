package com.thomas.notiguide.core.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.device.DeviceMqttPublisher
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.net.ssl.SSLContext

@Configuration
@ConditionalOnProperty(prefix = "mqtt", name = ["broker"], matchIfMissing = false)
class MqttConfig(private val props: MqttProperties) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Bean
    fun mqttConnectionOptions(): MqttConnectionOptions =
        MqttConnectionOptions().apply {
            isCleanStart = true
            keepAliveInterval = props.keepAliveSeconds
            connectionTimeout = props.connectionTimeoutSeconds
            isAutomaticReconnect = props.autoReconnect
            maxReconnectDelay = props.maxReconnectDelaySeconds

            if (props.username.isNotBlank()) {
                userName = props.username
            }
            if (props.password.isNotBlank()) {
                password = props.password.toByteArray()
            }

            if (props.broker.startsWith("ssl://")) {
                socketFactory = SSLContext.getDefault().socketFactory
                logger.info("MQTTS enabled for broker {}", props.broker)
            }
        }

    @Bean
    fun mqttAsyncClient(): MqttAsyncClient {
        val uniqueClientId = "${props.clientId}-${UUID.randomUUID().toString().take(8)}"
        return MqttAsyncClient(props.broker, uniqueClientId, MemoryPersistence())
    }

    @Bean(initMethod = "connect", destroyMethod = "disconnect")
    fun mqttClientManager(
        client: MqttAsyncClient,
        options: MqttConnectionOptions
    ): MqttClientManager = MqttClientManager(client, options, props)

    @Bean
    fun mqttPublisher(
        clientManager: MqttClientManager,
        objectMapper: ObjectMapper
    ): MqttPublisher = MqttPublisher(clientManager, props, objectMapper)

    @Bean
    fun deviceMqttPublisher(
        clientManager: MqttClientManager,
        objectMapper: ObjectMapper
    ): DeviceMqttPublisher = DeviceMqttPublisher(clientManager, props, objectMapper)
}
