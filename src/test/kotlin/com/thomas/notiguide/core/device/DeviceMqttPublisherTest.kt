package com.thomas.notiguide.core.device

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.core.mqtt.MqttClientManager
import com.thomas.notiguide.core.mqtt.MqttProperties
import com.thomas.notiguide.domain.device.types.DeviceKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DeviceMqttPublisherTest {
    private val mqtt = mockk<MqttClientManager>(relaxed = true)
    private val props = mockk<MqttProperties> { every { topicPrefix } returns "notiguide" }
    private val publisher = DeviceMqttPublisher(mqtt, props, jacksonObjectMapper())

    @Test
    fun `challenge publishes to the nonce topic and omits challenge_id and registration_nonce`() = runTest {
        val topic = slot<String>()
        val body = slot<ByteArray>()
        // MqttClientManager.publish(topic, payload, qos, retained) is a plain (non-suspend) fun.
        every { mqtt.publish(capture(topic), capture(body), any(), any()) } returns Unit

        publisher.publishChallenge(
            kind = DeviceKind.TRANSMITTER_HUB,
            registrationNonce = "NONCE_abc-1",
            nonce = "server16b",
            issuedAt = OffsetDateTime.parse("2026-07-09T09:00:00Z"),
            expiresAt = OffsetDateTime.parse("2026-07-09T09:05:00Z")
        )

        assertThat(topic.captured).isEqualTo("notiguide/transmitter/bootstrap/NONCE_abc-1")
        val json = String(body.captured)
        assertThat(json).contains("\"type\":\"challenge\"", "\"nonce\":\"server16b\"", "\"purpose\":\"activate-v1\"")
        assertThat(json).doesNotContain("challenge_id", "registration_nonce")
    }
}
