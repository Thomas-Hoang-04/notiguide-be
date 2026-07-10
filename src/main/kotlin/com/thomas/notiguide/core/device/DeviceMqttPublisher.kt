package com.thomas.notiguide.core.device

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.types.DeviceFamily
import com.thomas.notiguide.domain.device.types.DeviceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

class DeviceMqttPublisher(
    private val mqttClientManager: MqttClientManager,
    private val mqttProperties: MqttProperties,
    private val objectMapper: ObjectMapper
) {

    suspend fun publishPending(
        family: DeviceFamily,
        registrationNonce: String,
        issuedAt: OffsetDateTime
    ) {
        publishJson(
            topic = bootstrapTopic(family, registrationNonce),
            payload = PendingEnvelope(issuedAt = issuedAt.toInstant().toString())
        )
    }

    suspend fun publishRejected(
        family: DeviceFamily,
        registrationNonce: String,
        reason: String
    ) {
        publishJson(
            topic = bootstrapTopic(family, registrationNonce),
            payload = RejectedEnvelope(reason = reason)
        )
    }

    suspend fun publishRejected(
        kind: DeviceKind,
        registrationNonce: String,
        reason: String
    ) {
        publishJson(
            topic = bootstrapTopic(kind, registrationNonce),
            payload = RejectedEnvelope(reason = reason)
        )
    }

    suspend fun publishChallenge(
        kind: DeviceKind,
        registrationNonce: String,
        nonce: String,
        issuedAt: OffsetDateTime,
        expiresAt: OffsetDateTime
    ) {
        publishJson(
            topic = bootstrapTopic(kind, registrationNonce),
            payload = ChallengeEnvelope(
                nonce = nonce,
                issuedAt = issuedAt.toInstant().toString(),
                expiresAt = expiresAt.toInstant().toString()
            )
        )
    }

    suspend fun publishResult(
        kind: DeviceKind,
        registrationNonce: String,
        publicId: String,
        assignedDeviceName: String,
        storeId: String? = null
    ) {
        publishJson(
            topic = bootstrapTopic(kind, registrationNonce),
            payload = ResultEnvelope(
                publicId = publicId,
                assignedDeviceName = assignedDeviceName,
                storeId = storeId
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

    suspend fun publishRosterAck(
        hubPublicId: String,
        payload: Any
    ) {
        publishJson(
            topic = "${mqttProperties.topicPrefix}/transmitter/hub/$hubPublicId/roster/ack",
            payload = payload,
            retained = false
        )
    }

    suspend fun publishLabel(
        hubPublicId: String,
        payload: Any
    ) {
        publishJson(
            topic = "${mqttProperties.topicPrefix}/transmitter/hub/$hubPublicId/cmd/label",
            payload = payload,
            retained = false
        )
    }

    suspend fun publishUnpair(
        hubPublicId: String,
        payload: Any
    ) {
        publishJson(
            topic = "${mqttProperties.topicPrefix}/transmitter/hub/$hubPublicId/cmd/unpair",
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

    private fun bootstrapTopic(family: DeviceFamily, registrationNonce: String): String =
        "${mqttProperties.topicPrefix}/${family.topicSegment}/bootstrap/$registrationNonce"

    private fun bootstrapTopic(kind: DeviceKind, registrationNonce: String): String =
        bootstrapTopic(
            family = if (kind.isHub()) DeviceFamily.TRANSMITTER else DeviceFamily.RECEIVER,
            registrationNonce = registrationNonce
        )

    private data class PendingEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "pending",
        @field:JsonProperty("issued_at")
        val issuedAt: String
    )

    private data class RejectedEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "rejected",
        val reason: String
    )

    private data class ChallengeEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "challenge",
        val nonce: String,
        @field:JsonProperty("issued_at")
        val issuedAt: String,
        @field:JsonProperty("expires_at")
        val expiresAt: String,
        val purpose: String = "activate-v1"
    )

    private data class ResultEnvelope(
        @field:JsonProperty("schema_version")
        val schemaVersion: Int = 1,
        val type: String = "result",
        val status: String = "active",
        @field:JsonProperty("public_id")
        val publicId: String,
        @field:JsonProperty("assigned_device_name")
        val assignedDeviceName: String,
        @field:JsonProperty("store_id")
        val storeId: String? = null
    )
}
