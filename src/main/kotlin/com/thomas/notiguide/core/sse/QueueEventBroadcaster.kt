package com.thomas.notiguide.core.sse

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttMessageHandler
import com.thomas.notiguide.core.mqtt.MqttProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.UUID

data class QueueSseEvent(
    val type: String,
    val storeId: UUID,
    val ticketId: UUID,
    val ticketNumber: String? = null,
    val counterId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Component
class QueueEventBroadcaster(
    private val objectMapper: ObjectMapper,
    private val mqttClientManager: MqttClientManager? = null,
    private val mqttProperties: MqttProperties? = null
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val sink: Sinks.Many<QueueSseEvent> =
        Sinks.many().multicast().directBestEffort()

    private val mqttHandler = MqttMessageHandler { topic, message ->
        handleMqttMessage(topic, message)
    }

    @PostConstruct
    fun init() {
        if (mqttClientManager != null && mqttProperties != null) {
            mqttClientManager.registerHandler(mqttHandler)
            mqttClientManager.subscribe("${mqttProperties.topicPrefix}/store/+/queue")
            log.info("QueueEventBroadcaster subscribed to MQTT queue events")
        } else {
            log.info("QueueEventBroadcaster running in local-only mode (MQTT not configured)")
        }
    }

    @PreDestroy
    fun destroy() {
        if (mqttClientManager != null && mqttProperties != null) {
            mqttClientManager.unsubscribe("${mqttProperties.topicPrefix}/store/+/queue")
            mqttClientManager.removeHandler(mqttHandler)
        }
    }

    /**
     * Broadcast a queue event from this instance (called by QueueService directly).
     */
    fun broadcast(event: QueueSseEvent) {
        sink.tryEmitNext(event)
    }

    /**
     * Subscribe to SSE events for a specific store.
     */
    fun streamForStore(storeId: UUID): Flux<QueueSseEvent> =
        sink.asFlux().filter { it.storeId == storeId }

    private fun handleMqttMessage(topic: String, message: MqttMessage) {
        try {
            val event = objectMapper.readValue(message.payload, QueueSseEvent::class.java)
            sink.tryEmitNext(event)
        } catch (e: Exception) {
            log.warn("Failed to deserialize MQTT queue event from {}: {}", topic, e.message)
        }
    }
}
