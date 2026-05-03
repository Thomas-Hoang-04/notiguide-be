package com.thomas.notiguide.domain.device.listener

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.service.DeviceLifecycleService
import com.thomas.notiguide.domain.device.service.RfCodeService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(MqttClientManager::class)
class DeviceOperationalListener(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper,
    private val rfCodeService: RfCodeService,
    private val deviceLifecycleService: DeviceLifecycleService
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val topicFilter by lazy { "${mqttProperties.topicPrefix}/receiver/device/+/ack" }

    private val handler = MqttMessageHandler { topic, message ->
        val publicId = topic.removePrefix("${mqttProperties.topicPrefix}/receiver/device/")
            .removeSuffix("/ack")
        if (publicId == topic) {
            return@MqttMessageHandler
        }

        val payload = String(message.payload, Charsets.UTF_8)
        val ackFor = runCatching {
            objectMapper.readValue(payload, AckDispatchEnvelope::class.java).ackFor
        }.getOrElse {
            log.warn("Ignoring malformed receiver ack on topic {}", topic, it)
            return@MqttMessageHandler
        }

        scope.launch {
            runCatching {
                when (ackFor) {
                    "rf_code" -> rfCodeService.onAck(publicId, payload)
                    "deact" -> deviceLifecycleService.onAck(publicId, payload)
                }
            }.onFailure { ex ->
                log.warn("Receiver operational ack handling failed for {}", publicId, ex)
            }
        }
    }

    override fun afterSingletonsInstantiated() {
        mqttClientManager.registerHandler(handler)
        mqttClientManager.subscribe(topicFilter)
    }

    @PreDestroy
    fun destroy() {
        mqttClientManager.unsubscribe(topicFilter)
        mqttClientManager.removeHandler(handler)
        scope.cancel()
    }
}

private data class AckDispatchEnvelope(
    @field:JsonProperty("ack_for")
    val ackFor: String = ""
)
