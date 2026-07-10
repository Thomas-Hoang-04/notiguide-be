package com.thomas.notiguide.domain.device.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.service.DeviceActivationService
import com.thomas.notiguide.domain.device.service.DeviceRegistrationService
import com.thomas.notiguide.domain.device.types.DeviceFamily
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(MqttClientManager::class)
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class TransmitterBootstrapListener(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper,
    private val deviceRegistrationService: DeviceRegistrationService,
    private val deviceActivationService: DeviceActivationService
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val topicFilter by lazy { "${mqttProperties.topicPrefix}/transmitter/bootstrap/+" }
    private val registerTopic by lazy { "${mqttProperties.topicPrefix}/transmitter/bootstrap/register" }

    override fun afterSingletonsInstantiated() {
        mqttClientManager.registerHandler(handler)
        mqttClientManager.subscribe(
            MqttClientManager.SubscriptionDescriptor(
                topicFilter = topicFilter,
                qos = 1,
                noLocal = true
            )
        )
    }

    @PreDestroy
    fun destroy() {
        mqttClientManager.unsubscribe(topicFilter)
        mqttClientManager.removeHandler(handler)
        scope.cancel()
    }

    private val handler = MqttMessageHandler { topic, message ->
        if (topic == registerTopic) {
            scope.launch {
                runCatching {
                    deviceRegistrationService.onRegister(
                        payload = String(message.payload, Charsets.UTF_8),
                        family = DeviceFamily.TRANSMITTER
                    )
                }.onFailure { ex ->
                    log.warn("Transmitter bootstrap registration handling failed", ex)
                }
            }
            return@MqttMessageHandler
        }

        val payloadStr = String(message.payload, Charsets.UTF_8)
        val envelopeType = runCatching {
            objectMapper.readValue(payloadStr, BootstrapEnvelopeType::class.java).type
        }.getOrNull() ?: return@MqttMessageHandler

        if (envelopeType != "response") {
            return@MqttMessageHandler
        }

        val registrationNonce = topic
            .removePrefix("${mqttProperties.topicPrefix}/transmitter/bootstrap/")
            .takeIf { it.isNotBlank() && it != topic } ?: return@MqttMessageHandler

        scope.launch {
            runCatching {
                deviceActivationService.onResponse(
                    payload = payloadStr,
                    registrationNonce = registrationNonce
                )
            }.onFailure { ex ->
                log.warn("Transmitter activation response handling failed", ex)
            }
        }
    }
}
