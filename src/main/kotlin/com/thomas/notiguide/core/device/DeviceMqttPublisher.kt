package com.thomas.notiguide.core.device

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.types.DeviceFamily
import com.thomas.notiguide.domain.device.types.DeviceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
@ConditionalOnBean(MqttClientManager::class)
class DeviceMqttPublisher(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper
) {

    suspend fun publishPending(
        family: DeviceFamily,
        challengeId: UUID,
        registrationNonce: String,
        issuedAt: OffsetDateTime
    ) {
        publishJson(
            topic = bootstrapTopic(family, challengeId),
            payload = PendingEnvelope(
                challengeId = challengeId,
                registrationNonce = registrationNonce,
                issuedAt = issuedAt.toInstant().toString()
            )
        )
    }

    suspend fun publishRejected(
        family: DeviceFamily,
        challengeId: UUID,
        reason: String
    ) {
        publishJson(
            topic = bootstrapTopic(family, challengeId),
            payload = RejectedEnvelope(
                challengeId = challengeId,
                reason = reason
            )
        )
    }

    suspend fun publishRfCode(
        publicId: String,
        payload: Any
    ) {
        publishJson(
            topic = "${mqttProperties.topicPrefix}/receiver/device/$publicId/cmd/rf_code",
            payload = payload,
            retained = true
        )
    }

    suspend fun publishDeact(
        publicId: String,
        kind: DeviceKind,
        payload: Any
    ) {
        val topic = if (kind.isHub()) {
            "${mqttProperties.topicPrefix}/transmitter/hub/$publicId/cmd/deact"
        } else {
            "${mqttProperties.topicPrefix}/receiver/device/$publicId/cmd/deact"
        }
        publishJson(topic = topic, payload = payload, retained = true)
    }

    suspend fun publishTransmit(
        hubPublicId: String,
        payload: Any
    ) {
        publishJson(
            topic = "${mqttProperties.topicPrefix}/transmitter/hub/$hubPublicId/cmd/transmit",
            payload = payload,
            retained = false
        )
    }

    suspend fun clearRetained(
        publicId: String,
        kind: DeviceKind
    ) {
        if (kind.isHub()) {
            publishRaw(
                topic = "${mqttProperties.topicPrefix}/transmitter/hub/$publicId/cmd/deact",
                payload = ByteArray(0),
                retained = true
            )
            return
        }

        publishRaw(
            topic = "${mqttProperties.topicPrefix}/receiver/device/$publicId/cmd/rf_code",
            payload = ByteArray(0),
            retained = true
        )
        publishRaw(
            topic = "${mqttProperties.topicPrefix}/receiver/device/$publicId/cmd/deact",
            payload = ByteArray(0),
            retained = true
        )
    }

    private suspend fun publishJson(
        topic: String,
        payload: Any,
        retained: Boolean = false
    ) {
        publishRaw(topic, objectMapper.writeValueAsBytes(payload), retained)
    }

    private suspend fun publishRaw(
        topic: String,
        payload: ByteArray,
        retained: Boolean
    ) {
        withContext(Dispatchers.IO) {
            mqttClientManager.publish(topic, payload, qos = 1, retained = retained)
        }
    }

    private fun bootstrapTopic(family: DeviceFamily, challengeId: UUID): String =
        "${mqttProperties.topicPrefix}/${family.topicSegment}/bootstrap/$challengeId"

    private data class PendingEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "pending",
        @field:JsonProperty("challenge_id")
        val challengeId: UUID,
        @field:JsonProperty("registration_nonce")
        val registrationNonce: String,
        @field:JsonProperty("issued_at")
        val issuedAt: String
    )

    private data class RejectedEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "rejected",
        @field:JsonProperty("challenge_id")
        val challengeId: UUID,
        val reason: String
    )
}
