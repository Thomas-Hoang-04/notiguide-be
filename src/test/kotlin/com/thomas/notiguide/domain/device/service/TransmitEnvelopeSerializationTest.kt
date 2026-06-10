package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.thomas.notiguide.domain.device.dto.TransmitEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TransmitEnvelopeSerializationTest {

    private val mapper = jacksonObjectMapper()

    private fun envelope(deviceName: String?) = TransmitEnvelope(
        dispatchId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        receiverPublicId = "rx-abc123",
        band = "433M",
        rfCodeHex = "A1B2C3",
        rfCodeBits = 24,
        protoAny = false,
        issuedAt = "2026-06-09T10:00:00Z",
        deviceName = deviceName,
        signatureB64 = "c2ln"
    )

    @Test
    fun `device_name is serialized when assignedName is present`() {
        val node = mapper.readTree(mapper.writeValueAsString(envelope("Table 5")))
        assertThat(node.has("device_name")).isTrue()
        assertThat(node.get("device_name").asText()).isEqualTo("Table 5")
    }

    @Test
    fun `device_name key is omitted when assignedName is null`() {
        val node = mapper.readTree(mapper.writeValueAsString(envelope(null)))
        assertThat(node.has("device_name")).isFalse()
    }

    @Test
    fun `signed fields remain present with snake_case names`() {
        val node = mapper.readTree(mapper.writeValueAsString(envelope("Table 5")))
        assertThat(node.has("receiver_public_id")).isTrue()
        assertThat(node.has("rf_code_hex")).isTrue()
        assertThat(node.has("signature_b64")).isTrue()
        assertThat(node.get("schema_version").asInt()).isEqualTo(1)
    }
}
