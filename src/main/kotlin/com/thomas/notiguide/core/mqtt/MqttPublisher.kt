package com.thomas.notiguide.core.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnBean(MqttClientManager::class)
class MqttPublisher(
    private val clientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun publishQueueEvent(storeId: UUID, event: QueueEvent) {
        val topic = "${mqttProperties.topicPrefix}/store/${storeId}/queue"
        val payload = objectMapper.writeValueAsBytes(event)
        withContext(Dispatchers.IO) {
            clientManager.publish(topic, payload)
        }
        log.debug("MQTT queue event published: store={} type={}", storeId, event.type)
    }

    data class QueueEvent(
        val type: QueueEventType,
        val storeId: UUID,
        val ticketId: UUID,
        val ticketNumber: String? = null,
        val counterId: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class QueueEventType {
        TICKET_ISSUED,
        TICKET_CALLED,
        TICKET_SERVED,
        TICKET_CANCELLED
    }
}
